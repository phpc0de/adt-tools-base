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
package com.android.ide.common.gradle.model;

import com.android.annotations.NonNull;
import com.android.builder.model.AndroidLibrary;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.Collection;
import java.util.Objects;

/** Creates a deep copy of an {@link AndroidLibrary}. */
public final class IdeAndroidLibrary extends IdeAndroidBundle implements AndroidLibrary {
    // Increase the value when adding/removing fields or when changing the serialization/deserialization mechanism.
    private static final long serialVersionUID = 1L;

    @NonNull private final Collection<File> myLocalJars;
    @NonNull private final File myProguardRules;
    @NonNull private final File myLintJar;
    @NonNull private final File myPublicResources;
    private final int myHashCode;

    public IdeAndroidLibrary(@NonNull AndroidLibrary library, @NonNull ModelCache modelCache) {
        super(library, modelCache);
        myLocalJars = ImmutableList.copyOf(library.getLocalJars());
        myProguardRules = library.getProguardRules();
        myLintJar = library.getLintJar();
        myPublicResources = library.getPublicResources();

        myHashCode = calculateHashCode();
    }

    // for serialization
    @SuppressWarnings({"unused", "ConstantConditions"})
    private IdeAndroidLibrary() {
        super();
        myLocalJars = null;
        myProguardRules = null;
        myLintJar = null;
        myPublicResources = null;
        myHashCode = 0;
    }

    @Override
    @NonNull
    public Collection<File> getLocalJars() {
        return myLocalJars;
    }

    @Override
    @NonNull
    public File getJniFolder() {
        throw new UnusedModelMethodException("getJniFolder");
    }

    @Override
    @NonNull
    public File getAidlFolder() {
        throw new UnusedModelMethodException("getRenderscriptFolder");
    }

    @Override
    @NonNull
    public File getRenderscriptFolder() {
        throw new UnusedModelMethodException("getRenderscriptFolder");
    }

    @Override
    @NonNull
    public File getProguardRules() {
        return myProguardRules;
    }

    @Override
    @NonNull
    public File getLintJar() {
        return myLintJar;
    }

    @Override
    @NonNull
    public File getExternalAnnotations() {
        throw new UnusedModelMethodException("getExternalAnnotations");
    }

    @Override
    @NonNull
    public File getPublicResources() {
        return myPublicResources;
    }

    @Override
    @NonNull
    public File getSymbolFile() {
        throw new UnusedModelMethodException("getSymbolFile");
    }

    @Override
    @Deprecated
    public boolean isOptional() {
        throw new UnusedModelMethodException("isOptional");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IdeAndroidLibrary)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        IdeAndroidLibrary library = (IdeAndroidLibrary) o;
        return library.canEqual(this)
                && Objects.equals(myLocalJars, library.myLocalJars)
                && Objects.equals(myProguardRules, library.myProguardRules)
                && Objects.equals(myLintJar, library.myLintJar)
                && Objects.equals(myPublicResources, library.myPublicResources);
    }

    @Override
    public boolean canEqual(Object other) {
        return other instanceof IdeAndroidLibrary;
    }

    @Override
    public int hashCode() {
        return myHashCode;
    }

    @Override
    protected int calculateHashCode() {
        return Objects.hash(
                super.calculateHashCode(),
                myLocalJars,
                myProguardRules,
                myLintJar,
                myPublicResources);
    }

    @Override
    public String toString() {
        return "IdeAndroidLibrary{"
                + super.toString()
                + ", myLocalJars="
                + myLocalJars
                + ", myProguardRules="
                + myProguardRules
                + ", myLintJar="
                + myLintJar
                + ", myPublicResources="
                + myPublicResources
                + "}";
    }
}
