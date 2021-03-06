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

#include "utils/file_cache.h"
#include "utils/fs/memory_file_system.h"

#include <gtest/gtest.h>

using profiler::FileSystem;
using profiler::FileCache;
using profiler::MemoryFileSystem;
using std::unique_ptr;
using std::string;

TEST(FileCache, CanAddCacheByChunks) {
  FileCache cache(unique_ptr<FileSystem>(new MemoryFileSystem()), "/");

  cache.AddChunk("dummy-id", "123");
  cache.AddChunk("dummy-id", "456");
  cache.AddChunk("dummy-id", "789");

  // File not in cache until complete...
  EXPECT_EQ(cache.GetFile("dummy-id")->Contents(), "");

  auto file = cache.Complete("dummy-id");
  EXPECT_EQ(file->Contents(), "123456789");
  EXPECT_EQ(cache.GetFile("dummy-id")->Contents(), file->Contents());
}

TEST(FileCache, CanAbortAddingToCache) {
  FileCache cache(unique_ptr<FileSystem>(new MemoryFileSystem()), "/");
  cache.AddChunk("dummy-id", "123");
  cache.AddChunk("dummy-id", "456");
  cache.AddChunk("dummy-id", "789");
  cache.Abort("dummy-id");
  cache.AddChunk("dummy-id", "abc");
  cache.Complete("dummy-id");

  EXPECT_EQ(cache.GetFile("dummy-id")->Contents(), "abc");
}

TEST(FileCache, CanOverwriteCache) {
  FileCache cache(unique_ptr<FileSystem>(new MemoryFileSystem()), "/");

  cache.AddChunk("dummy-id", "123");
  auto file = cache.Complete("dummy-id");
  EXPECT_EQ(file->Contents(), "123");

  cache.AddChunk("dummy-id", "abc");
  file = cache.Complete("dummy-id");
  EXPECT_EQ(file->Contents(), "abc");
}

TEST(FileCache, CanAddStringsToCache) {
  FileCache cache(unique_ptr<FileSystem>(new MemoryFileSystem()), "/");

  auto str_a = string("This is my first string");
  auto str_b = string("This is my second string");

  auto cache_id1 = cache.AddString(str_a);
  auto cache_id2 = cache.AddString(str_b);
  auto cache_id3 = cache.AddString(str_a);

  auto file1 = cache.GetFile(cache_id1);
  auto file2 = cache.GetFile(cache_id2);
  auto file3 = cache.GetFile(cache_id3);

  EXPECT_EQ(file1->Contents(), str_a);
  EXPECT_EQ(file2->Contents(), str_b);
  EXPECT_EQ(file3->Contents(), str_a);

  EXPECT_NE(cache_id1, cache_id2);
  EXPECT_EQ(cache_id1, cache_id3);
}
