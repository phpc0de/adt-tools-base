/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.testutils.filesystemdiff;

import com.android.utils.ILogger;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public class DeleteDirectoryAction extends Action {
    private DirectoryEntry mEntry;

    public DeleteDirectoryAction(DirectoryEntry entry) {
        mEntry = entry;
    }

    @Override
    public DirectoryEntry getSourceEntry() {
        return mEntry;
    }

    @Override
    public void execute(ILogger logger) {
        try {
            Files.walkFileTree(mEntry.getPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                  throws IOException {
                    logger.verbose("Deleting file %s", file);
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                  throws IOException {
                    if (exc != null) {
                      throw exc;
                    }
                    logger.verbose("Deleting directory %s", dir);
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
