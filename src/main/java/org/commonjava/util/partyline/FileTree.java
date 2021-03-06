/**
 * Copyright (C) 2015 Red Hat, Inc. (jdcasey@commonjava.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.util.partyline;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.commonjava.cdi.util.weft.SignallingLock;
import org.commonjava.cdi.util.weft.SignallingLocker;
import org.commonjava.util.partyline.callback.StreamCallbacks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.commonjava.util.partyline.ExceptionUtils.handleError;
import static org.commonjava.util.partyline.LockLevel.read;
import static org.commonjava.util.partyline.LockOwner.getLockReservationName;

/**
 * Maintains access to files in partyline. This class restricts operations to prohibit concurrent operations on the same
 * path at the same time, where 'operation' means opening a file, deleting a file, or locking/unlocking a file. It also
 * provides methods for extracting or logging information about the file locks that are currently active.
 */
final class FileTree
{

    public static final long DEFAULT_LOCK_TIMEOUT = 5000;

    private static final long WAIT_TIMEOUT = 100;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final Map<String, FileEntry> entryMap = new ConcurrentHashMap<>();

    private final SignallingLocker<String> operationLocks = new SignallingLocker<>();

    /**
     * Iterate all {@link FileEntry instances} to extract information about active locks.
     *
     * @param fileConsumer The operation to extract information from a single active file.
     */
    void forAll( Consumer<JoinableFile> fileConsumer )
    {
        forAll( entry -> entry.file != null, entry->fileConsumer.accept( entry.file ) );
    }

    /**
     * Iterate all {@link FileEntry instances} to extract information about active locks.
     *
     * @param predicate The selector determining which files to analyze.
     * @param fileConsumer The operation to extract information from a single active file.
     */
    void forAll( Predicate<? super FileEntry> predicate, Consumer<FileEntry> fileConsumer )
    {
        TreeMap<String, FileEntry> sorted = new TreeMap<>( entryMap );
        sorted.forEach( ( key, entry ) -> {
            if ( entry != null && predicate.test( entry ) )
            {
                fileConsumer.accept( entry );
            }
        } );
    }

    /**
     * Render the active files as a tree structure, for output to a log file or other string-oriented output.
     */
    String renderTree()
    {
        StringBuilder sb = new StringBuilder();
        TreeMap<String, FileEntry> sorted = new TreeMap<>( entryMap );
        sorted.forEach( ( key, entry ) -> {
            sb.append( "+- " );
            Stream.of( key.split( "/" ) ).forEach( ( part ) -> sb.append( "  " ) );

            sb.append( new File( key ).getName() );
            if ( entry.file != null )
            {
                sb.append( " (F)" );
            }
            else
            {
                sb.append( "/" );
            }
        } );
        return sb.toString();
    }

    /**
     * Retrieve the {@link LockLevel} for the given file. This corresponds to the highest level of access currently
     * granted for this file.
     *
     * @see LockLevel
     */
    LockLevel getLockLevel( File file )
    {
        FileEntry entry = getLockingEntry( file );
        logger.trace( "Locking entry for file: {} is: {}", file, entry );
        String path = file.getAbsolutePath();
        if ( entry == null )
        {
            return null;
        }
        else if ( !entry.name.equals( path ) )
        {
            logger.trace( "Returning parent lock level lock due to parent lock (level: {})", entry.lock.getLockLevel() );
            return entry.lock.getLockLevel();
        }
        else
        {
            logger.trace( "Returning lock level for this file as: {}", entry.lock.getLockLevel() );
            return entry.lock.getLockLevel();
        }
    }

    int getContextLockCount( File file ){
        FileEntry entry = getLockingEntry( file );
        if ( entry == null )
        {
            return 0;
        }
        else if ( !entry.name.equals( file.getAbsolutePath() ) )
        {
            //FIXME: Not sure if this is also 0
            return 0;
        }
        else
        {
            return entry.lock.getContextLockCount();
        }
    }

    /**
     * (Manually) unlock a file for a given ownership label. The label allows the system to avoid unlocking for other
     * active threads that might still be using the file.
     * @param f The file to unlock
     * @param label The label for the lock to remove
     * @return true if the file has no remaining locks after unlocking for this owner; false otherwise
     */
    boolean unlock( File f, final String label )
    {
        return operationLocks.lockAnd( f.getAbsolutePath(), ( p, opLock ) -> {
            String ownerName = getLockReservationName();
            FileEntry entry = entryMap.get( f.getAbsolutePath() );
            if ( entry != null )
            {
                logger.trace( "Unlocking {} (owner: {})", f, ownerName );
                if ( entry.lock.unlock( label ) )
                {
                    logger.trace( "Unlocked; clearing resources associated with lock" );

                    closeEntryFile( entry, ownerName );

                    if ( !unlockAssociatedEntries( entry, label ) )
                    {
                        return false;
                    }

                    if ( !entry.lock.isLocked() )
                    {
                        entryMap.remove( entry.name );
                    }

                    opLock.signal();
                    logger.trace( "Unlock succeeded." );
                    return true;
                }
                else
                {
                    logger.trace( "{} Request did not completely unlock file. Remaining locks:\n\n{}", ownerName,
                                  entry.lock.getLockInfo() );
                    opLock.signal();
                    return false;
                }
            }
            else
            {
                logger.trace( "{} not locked by {}", f, ownerName );
            }

            opLock.signal();
            return true;
        } );
    }

    private boolean unlockAssociatedEntries( final FileEntry entry, final String label )
    {
        // the 'alsoLocked' entry field constitutes a linked list of locked entries.
        // When we unlock the topmost one, we need to unlock the ones that are linked too.
        FileEntry alsoLocked = entry.alsoLocked;
        while ( alsoLocked != null )
        {
            logger.trace( "ALSO Unlocking: {}", alsoLocked.name );
            alsoLocked.lock.unlock( label );
//
//            {
//                // FIXME: This is probably a little bit wrong, but in practice it should never fail.
//                // I'm not sure how we should handle failure to decrement the lock count for this
//                // ThreadContext. Should it cause the main unlock() method here to fail? Probably...
//                logger.error( "FAILED to unlock associated entry for path: {}\n\nEntry: {}\n\n", alsoLocked, entry.name );
//
//                opLock.signal();
//                logger.trace( "Unlock failed for: {}", entry.name );
//                return false;
//            }

            if ( !alsoLocked.lock.isLocked() )
            {
                entryMap.remove( alsoLocked.name );
            }

            alsoLocked = alsoLocked.alsoLocked;
        }

        return true;
    }

    /**
     * In certain cases, when an operation completes we cannot retain any locks on the file. This method clears all
     * remaining locks and releases the file from the active-locked mapping. The cases where this is important:
     *
     * <ul>
     *     <li>When the entire {@link JoinableFile} instance (which manages read and write operations) is closing</li>
     *     <li>When the completing operation locked a file for deletion</li>
     *     <li>When we've just established the first lock on a file, then the operation acquiring this lock fails.</li>
     * </ul>
     *
     * @param f The file whose locks should be cleared
     */
    private void clearLocks( final File f, final String label )
    {
        operationLocks.lockAnd( f.getAbsolutePath(), ( p, opLock ) -> {
            FileEntry entry = entryMap.get( f.getAbsolutePath() );
            if ( entry != null )
            {
                logger.trace( "Unlocking {}", f );
                entry.lock.clearLocks();
                logger.trace( "Unlocked; clearing resources associated with lock" );

                closeEntryFile( entry, "" );

                unlockAssociatedEntries( entry, label );

                entryMap.remove( entry.name );

                opLock.signal();
                logger.trace( "Unlock succeeded." );
            }
            else
            {
                logger.trace( "{} not locked", f );
            }

            opLock.signal();
            return null;
        } );
    }

    private void closeEntryFile( FileEntry entry, String extraTraceMsg )
    {
        if ( entry.file != null )
        {
            logger.trace( "{} Closing file...", extraTraceMsg == null ? "" : extraTraceMsg );
            IOUtils.closeQuietly( entry.file );
            entry.file = null;
        }
    }

    /**
     * Acquire the given {@link LockLevel} on the specified file, under the provided ownership name and activity label,
     * within the given timeout. This is used to manually lock a file from outside.
     *
     * @param file The file to lock
     * @param label The activity label, to aid in debugging stuck locks
     * @param lockLevel The type of lock to acquire (read, write, delete)
     * @param timeout The timeout period before giving up on the lock acquisition
     * @param unit The time units for the timeout period (milliseconds, etc)
     * @return true if the file was locked as specified, otherwise false
     * @throws InterruptedException
     *
     * @see JoinableFileManager#lock(File, long, LockLevel, String)
     * @see LockLevel
     */
    boolean tryLock( File file, String label, LockLevel lockLevel, long timeout, TimeUnit unit )
            throws InterruptedException
    {
        try
        {
            return tryLock( file, label, lockLevel, timeout, unit, ( p, e, opLock ) -> true );
        }
        catch ( IOException e )
        {
            logger.error( "SHOULD NEVER HAPPEN: IOException trying to lock: " + file, e );
        }

        return false;
    }

    /**
     * Acquire the given {@link LockLevel} on the specified file, under the provided ownership name and activity label,
     * within the given timeout. If lock acquisition succeeds, execute the provided operation (normally a lambda).
     * <br/>
     * This method is used within FileTree to handle file lock acquisition before opening / deleting files, among other
     * things.
     * <br/>
     * <b>NOTE:</b> Before attempting to acquire the file lock, this method will acquire the operation semaphore for
     * the given file. This prevents other concurrent calls from overlapping when establishing the first lock on a file,
     * or when releasing the last lock.
     *
     * @param f The file to lock
     * @param label The activity label, to aid in debugging stuck locks
     * @param lockLevel The type of lock to acquire (read, write, delete)
     * @param timeout The timeout period before giving up on the lock acquisition
     * @param unit The time units for the timeout period (milliseconds, etc)
     * @param operation The operation to perform once the file lock is acquired
     * @return the result of the provided operation, or else null
     * @throws InterruptedException
     *
     * @see LockLevel
     */
    private <T> T tryLock( File f, String label, LockLevel lockLevel, long timeout, TimeUnit unit,
                           LockedFileOperation<T> operation )
            throws InterruptedException, IOException
    {
        AtomicReference<Exception> error = new AtomicReference<>();
        T opResult = operationLocks.lockAnd( f.getAbsolutePath(), TimeUnit.SECONDS.convert( timeout, unit ), ( path, opLock ) -> {
            long end = timeout < 1 ? -1 : System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert( timeout, unit );

            logger.trace( "{}: Trying to lock until: {}", System.currentTimeMillis(), end );

            String name = f.getAbsolutePath();
            FileEntry entry = null;
            try
            {
                while ( end < 1 || System.currentTimeMillis() < end )
                {
                    entry = getLockingEntry( f );

                    /*
                    There are three basic states we need to capture here:

                    1. The target file is already locked. Try to lock again, and retry / fail as appropriate.

                    2. The target file's ancestor is locked. Try to lock again, and retry / fail as appropriate.
                       When locked, set a flag to tell the system to lock the target file and proceed.

                    3. Neither the target file nor its ancestry is locked. Set a flag to tell the system to lock the
                       target file and proceed.
                     */
                    boolean doFileLock = (entry == null);

                    if ( !doFileLock )
                    {
                        if ( entry.name.equals( name ) )
                        {
                            if ( entry.lock.lock( label, lockLevel ) )
                            {
                                logger.trace( "Added lock to existing entry: {}", entry.name );
                                T result = operation.apply( path, error, opLock );

                                Exception e = error.get();
                                if ( e != null )
                                {
                                    // we just locked this, and the call failed...reverse the lock operation.
                                    entry.lock.unlock( label );
                                }

                                return result;
                            }
                            else
                            {
                                logger.trace( "Lock failed, but retry may allow another attempt..." );
                            }
                        }
                        else if ( name.startsWith( entry.name ) )
                        {
                            logger.trace( "Re-locking the locking entry: {}.", entry.name );
                            entry.lock.lock( label, lockLevel );

                            FileEntry alsoLocked = entry.alsoLocked;
                            while ( alsoLocked != null )
                            {
                                logger.trace( "ALSO re-locking: {}", alsoLocked.name );
                                alsoLocked.lock.lock( label, read );
                                alsoLocked = alsoLocked.alsoLocked;
                            }

                            doFileLock = true;
                        }
                    }

                    /*
                    If we've been cleared to proceed above, create a new FileEntry instance, lock it, and proceed.
                     */
                    if ( error.get() == null && doFileLock )
                    {
                        if ( read == lockLevel && !f.exists() )
                        {
                            error.set( new IOException( f + " does not exist. Cannot read-lock missing file!" ) );
                        }

                        entry = new FileEntry( name, label, lockLevel, entry );
                        logger.trace( "No lock on {}; locking as: {} from: {} with also-locked: {}", name, lockLevel, label, entry.name );
                        entryMap.put( name, entry );
                        T result = operation.apply( path, error, opLock );

                        Exception e = error.get();
                        if ( e != null )
                        {
                            // we just locked this, and the call failed...reverse the lock operation.
                            // NOTE: This will CLEAR all locks, which is what we want since there was no FileEntry before.
                            clearLocks( f, label );
                        }

                        return result;
                    }
                    /*
                    If we haven't succeeded in locking the file (or its ancestry), wait.
                     */
                    else
                    {
                        logger.trace( "Waiting for lock to clear; locking as: {} from: {}", lockLevel, label );
                        try
                        {
                            opLock.await( WAIT_TIMEOUT );
                        }
                        catch ( InterruptedException e )
                        {
                            logger.warn( "Interrupted while waiting for {}", label );
                        }
                    }
                }
            }
            finally
            {
                // no matter what else happens, do NOT allow a delete lock to remain
                if ( entry != null && entry.lock.getLockLevel() == LockLevel.delete && entry.lock.isLocked() )
                {
                    logger.trace( "Clearing locks on delete-locked file entry: {}", f );
                    clearLocks( f, label );
                }
            }

            logger.trace( "{}: {}: Lock failed", System.currentTimeMillis(), name );
            return null;
        } );

        handleError( error, label );

        return opResult;
    }

    /**
     * Establish a Stream (input or output) associated with a given file. This method will acquire the appropriate lock
     * for the file (using {@link #tryLock(File, String, LockLevel, long, TimeUnit, LockedFileOperation)}) and
     * then retrieve the {@link JoinableFile} instance associated with the file (or create it if necessary). Finally,
     * it passes the JoinableFile to the given {@link JoinFileOperation} to establish the appropriate stream into / out
     * of that file.
     *
     * @param realFile The file to open
     * @param callbacks A set of callback operations that can respond to the file being closed or flushed, normally
     *                  used for accounting.
     * @param doOutput If true, the associated function should return an {@link java.io.OutputStream}; else {@link java.io.InputStream}
     * @param timeout The period to wait in attempting to acquire the appropriate file lock
     * @param unit The time unit for the timeout period
     * @param function The function that establishes the appropriate stream into the {@link JoinableFile}
     * @param <T> The type of stream returned from the given {@link JoinFileOperation}; output if doOutput is true, else input
     * @return The established stream associated with the given file
     * @throws IOException
     * @throws InterruptedException
     *
     * @see #tryLock(File, String, LockLevel, long, TimeUnit, LockedFileOperation)
     */
    <T> T setOrJoinFile( File realFile, StreamCallbacks callbacks, boolean doOutput, long timeout,
                                TimeUnit unit, JoinFileOperation<T> function )
            throws IOException, InterruptedException
    {
        long end = timeout < 1 ? -1 : System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert( timeout, unit );

        String label = JoinableFile.labelFor( doOutput, Thread.currentThread().getName() );
        while ( end < 1 || System.currentTimeMillis() < end )
        {
            T result = tryLock( realFile, label, doOutput ? LockLevel.write : read, timeout, unit,
                                ( path, error, opLock ) -> {
                                    FileEntry entry = entryMap.get( path );
                                    boolean proceed = false;
                                    if ( entry.file != null )
                                    {
                                        if ( doOutput )
                                        {
                                            error.set(
                                                    new IOException( "File already opened for writing: " + realFile ) );
                                        }
                                        else if ( !entry.file.isJoinable() )
                                        {
                                            // If we're joining the file and the file is in the process of closing, we need to wait and
                                            // try again once the file has finished closing.

                                            logger.trace(
                                                    "File open but in process of closing; not joinable. Will wait..." );

                                            // undo the lock we just placed on this entry, to allow it to clear...
                                            entry.lock.unlock( label );

                                            opLock.signal();

                                            logger.trace( "Waiting for file to close at: {}",
                                                          System.currentTimeMillis() );
                                            try
                                            {
                                                opLock.await( WAIT_TIMEOUT );
                                            }
                                            catch ( InterruptedException e )
                                            {
                                                error.set( e );
                                            }

                                            logger.trace( "Proceeding with lock attempt at: {} under opLock: {}",
                                                          System.currentTimeMillis(), opLock );
                                        }
                                        else
                                        {
                                            logger.trace( "Got joinable file" );
                                            proceed = true;
                                        }
                                    }
                                    else
                                    {
                                        logger.trace(
                                                "No pre-existing open file; opening new JoinableFile under opLock: {}",
                                                opLock );
                                        try
                                        {
                                            entry.file = new JoinableFile( realFile, entry.lock,
                                                                           new FileTreeCallbacks( callbacks, entry,
                                                                                                  realFile, label ),
                                                                           doOutput, operationLocks );
                                            proceed = true;
                                        }
                                        catch ( IOException e )
                                        {
                                            error.set( e );
                                            proceed = false;
                                        }

                                    }

                                    if ( proceed )
                                    {
                                        try
                                        {
                                            return function.execute( entry.file );
                                        }
                                        catch ( IOException e )
                                        {
                                            error.set( e );
                                        }
                                    }

                                    return null;
                                } );

            if ( result != null )
            {
                return result;
            }
        }

        logger.trace( "Failed to lock file for {}", doOutput ? "writing" : "reading" );
        return function.execute( null );
    }

    /**
     * Attempt to establish a delete lock on the given file, then delete it. Timeout if the specified period expires
     * without delete lock acquisition.
     *
     * @param file The file to lock
     * @param timeout The period to wait for a delete lock
     * @param unit The time unit for the timeout period
     * @return true if the delete lock was successful and the file was force-deleted; false otherwise
     * @throws InterruptedException
     * @throws IOException
     *
     * @see #tryLock(File, String, LockLevel, long, TimeUnit, LockedFileOperation)
     */
    boolean delete( File file, long timeout, TimeUnit unit )
            throws InterruptedException, IOException
    {
        return tryLock( file, "Delete File", LockLevel.delete, timeout, unit, ( path, error, opLock ) -> {
            entryMap.remove( file.getAbsolutePath() );
            //            synchronized ( this )
            //            {
            opLock.signal();
            //            }

            boolean result = true;
            if ( file.exists() )
            {
                try
                {
                    FileUtils.forceDelete( file );
                }
                catch ( IOException e )
                {
                    error.set( e );
                    result = false;
                }
            }

            return result;
        } );
    }

    /**
     * When trying to lock a file, we first must ensure that no directory further up the hierarchy is already locked with
     * a more restrictive lock. If we're trying to lock a directory, we also must ensure that no child directory/file
     * is locked with a more restrictive lock. This method checks for those cases, and returns the {@link FileEntry}
     * from the ancestry or descendent files/directories that is already locked.
     *
     * This should prevent us from deleting a directory when a child file within that directory structure is being read
     * or written. Likewise, it should prevent us from reading or writing a file in a directory already locked for
     * deletion.
     *
     * @param file The file whose context directories / files should be checked for locks
     * @return The nearest {@link FileEntry}, corresponding to a locked file. Parent directories returned before children.
     */
    private synchronized FileEntry getLockingEntry( File file )
    {
        FileEntry entry;

        // search self and ancestors...
        File f = file;
        do
        {
            entry = entryMap.get( f.getAbsolutePath() );
            if ( entry != null )
            {
                logger.trace( "Locked by: {}", entry.lock.getLockInfo() );
                return entry;
            }
            else
            {
                logger.trace( "No lock found for: {}", f );
            }

            f = f.getParentFile();
        }
        while ( f != null );

        // search for children...
        if ( file.isDirectory() )
        {
            String fp = file.getAbsolutePath();
            Optional<String> result =
                    entryMap.keySet().stream().filter( ( path ) -> path.startsWith( fp ) ).findFirst();
            if ( result.isPresent() )
            {
                logger.trace( "Child: {} is locked; returning child as locking entry", result.get() );
                return entryMap.get( result.get() );
            }
        }

        return null;
    }

    /**
     * Use a {@link java.util.concurrent.locks.ReentrantLock} keyed to the absolute path of the specified file to ensure
     * only one operation at a time manipulates the accounting information associated with the file ({@link FileEntry}).
     *
     * This method synchronizes on the operationLocks map in order to retrieve / create the ReentrantLock lazily. Once
     * created, this ReentrantLock also gets propagated into the {@link JoinableFile} instance created for the file.
     *
     * Using ReentrantLock per path avoids the need to hold a lock on the whole tree every time we need to initialize
     * the {@link FileEntry} for a new file. Instead, we take a short lock on operationLocks to get the ReentrantLock,
     * then use the ReentrantLock for the longer operations required to initialize a file, open a stream, delete a file,
     * close a file, etc.
     *
     * @param path The file path that is the subject of the operation we want to execute
     * @param op The operation to execute, once we've locked the ReentrantLock associated with the file
     * @param <T> The result type of the specified operation
     * @return the result of the specified operation
     * @throws IOException
     * @throws InterruptedException
     */
    private <T> T withOpLock( String path, BiFunction<String, SignallingLock, T> op )
            throws IOException, InterruptedException
    {
        return operationLocks.lockAnd( path, op );
    }

    public boolean isLockedByCurrentThread( final File file )
    {
        FileEntry fileEntry = entryMap.get( file.getAbsolutePath() );
        return fileEntry != null && fileEntry.lock.isLockedByCurrentThread();
    }

    /**
     * Class which manages the state associated with files and {@link JoinableFile}s in partyline. These keep the lock
     * associated with a path and a {@link JoinableFile}, even when there is no JoinableFile yet. They are mapped to the
     * path to allow concurrent operations to access this state and open additional (reader) streams, etc.
     */
    static final class FileEntry
    {
        private final String name;

        private FileEntry alsoLocked;

        private final LockOwner lock;

        private JoinableFile file;

        FileEntry( String name, String lockingLabel, LockLevel lockLevel, final FileEntry alsoLocked )
        {
            this.name = name;
            this.alsoLocked = alsoLocked;
            this.lock = new LockOwner( name, lockingLabel, lockLevel );
        }
    }

    /**
     * {@link StreamCallbacks} implementation which can wrap another instance passed into {@link FileTree} operations,
     * and which takes care of clearing all locks on a file when the {@link JoinableFile} is finally closed.
     */
    private final class FileTreeCallbacks
            implements StreamCallbacks
    {
        private StreamCallbacks callbacks;

        private File file;

        private FileEntry entry;

        private String label;

        public FileTreeCallbacks( StreamCallbacks callbacks, FileEntry entry, File file, final String label )
        {
            this.callbacks = callbacks;
            this.file = file;
            this.entry = entry;
            this.label = label;
        }

        @Override
        public void flushed()
        {
            if ( callbacks != null )
            {
                callbacks.flushed();
            }
        }

        @Override
        public void beforeClose()
        {
            if ( callbacks != null )
            {
                callbacks.beforeClose();
            }
        }

        @Override
        public void closed()
        {
            if ( callbacks != null )
            {
                callbacks.closed();
            }

            logger.trace( "unlocking: {}", file );

            // already inside lock from JoinableFile.reallyClose().
            entry.file = null;

            // the whole JoinableFile is closing. Clear remaining locks.
            clearLocks( file, label );
        }
    }

    /**
     * Operation that returns a stream (InputStream or OutputStream) from a {@link JoinableFile}. This is used from
     * {@link JoinableFileManager#openInputStream(File, long)} and {@link JoinableFileManager#openOutputStream(File, long)}
     * via {@link FileTree#setOrJoinFile(File, StreamCallbacks, boolean, long, TimeUnit, JoinFileOperation)}.
     *
     * @param <T> The stream result.
     *
     * @see JoinableFileManager#openInputStream(File, long)
     * @see JoinableFileManager#openOutputStream(File, long)
     * @see FileTree#setOrJoinFile(File, StreamCallbacks, boolean, long, TimeUnit, JoinFileOperation)
     */
    @FunctionalInterface
    interface JoinFileOperation<T>
    {
        T execute( JoinableFile file )
                throws IOException;
    }
}