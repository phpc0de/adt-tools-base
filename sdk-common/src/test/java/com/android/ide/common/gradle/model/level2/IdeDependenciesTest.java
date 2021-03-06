/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.ide.common.gradle.model.level2;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;

import com.android.ide.common.gradle.model.stubs.BaseArtifactStub;
import com.android.ide.common.repository.GradleVersion;
import com.android.testutils.Serialization;
import java.io.Serializable;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link IdeDependencies}. */
public class IdeDependenciesTest {
    IdeDependenciesFactory myDependenciesFactory;

    @Before
    public void setup() {
        myDependenciesFactory = new IdeDependenciesFactory();
    }

    @Test
    public void serializable() {
        assertThat(IdeDependenciesImpl.class).isAssignableTo(Serializable.class);
    }

    @Test
    public void serialization() throws Exception {
        IdeDependencies graphs =
                myDependenciesFactory.create(new BaseArtifactStub(), GradleVersion.parse("2.3.0"));
        byte[] bytes = Serialization.serialize(graphs);
        Object o = Serialization.deserialize(bytes);
        assertEquals(graphs, o);
    }
}
