/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.ide.common.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.testutils.TestResources;
import java.io.File;
import java.io.IOException;
import org.junit.Test;

public class AssetSetTest extends BaseTestCase {

    private static AssetSet sBaseResourceSet = null;

    @Test
    public void testBaseAssetSetByCount() throws Exception {
        AssetSet assetSet = getBaseAssetSet();
        assertEquals(4, assetSet.size());
    }

    @Test
    public void testBaseAssetSetByName() throws Exception {
        AssetSet assetSet = getBaseAssetSet();

        verifyResourceExists(assetSet,
                "foo/foo.dat",
                "foo/icon.png",
                "main.xml",
                "values.xml"
        );
    }

    @Test
    public void testDupAssetSet() throws Exception {
        File root = TestResources.getDirectory(getClass(), "/testData/assets/dupSet");

        AssetSet set = new AssetSet("main");
        set.addSource(new File(root, "assets1"));
        set.addSource(new File(root, "assets2"));
        boolean gotException = false;
        RecordingLogger logger = new RecordingLogger();
        try {
            set.loadFromFiles(logger);
        } catch (DuplicateDataException e) {
            gotException = true;
        }

        assertTrue(gotException);
        checkLogger(logger);
    }

    static AssetSet getBaseAssetSet() throws MergingException, IOException {
        if (sBaseResourceSet == null) {
            File root = TestResources.getDirectory(AssetSetTest.class, "/testData/assets/baseSet");

            RecordingLogger logger = new RecordingLogger();

            sBaseResourceSet = new AssetSet("main");
            sBaseResourceSet.addSource(root);
            sBaseResourceSet.loadFromFiles(logger);
            checkLogger(logger);
        }

        return sBaseResourceSet;
    }
}
