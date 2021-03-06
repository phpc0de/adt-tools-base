/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.build.gradle.internal.variant;


import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.VariantOutput;
import com.android.build.gradle.internal.TaskManager;
import com.android.build.gradle.internal.VariantModel;
import com.android.build.gradle.internal.api.BaseVariantImpl;
import com.android.build.gradle.internal.api.ReadOnlyObjectProvider;
import com.android.build.gradle.internal.core.GradleVariantConfiguration;
import com.android.build.gradle.internal.dsl.BuildType;
import com.android.build.gradle.internal.dsl.ProductFlavor;
import com.android.build.gradle.internal.dsl.SigningConfig;
import com.android.builder.core.VariantType;
import com.android.builder.profile.Recorder;
import java.util.Collection;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;

/**
 * Interface for Variant Factory.
 *
 * <p>While VariantManager is the general variant management, implementation of this interface
 * provides variant type (app, lib) specific implementation.
 */
public interface VariantFactory {

    @NonNull
    BaseVariantData createVariantData(
            @NonNull GradleVariantConfiguration variantConfiguration,
            @NonNull TaskManager taskManager,
            @NonNull Recorder recorder);

    //FIXME: Restore these to @NonNull when the instantApp plugin is simplified.
    @Nullable
    Class<? extends BaseVariantImpl> getVariantImplementationClass(
            @NonNull BaseVariantData variantData);

    @Nullable
    default BaseVariantImpl createVariantApi(
            @NonNull ObjectFactory objectFactory,
            @NonNull BaseVariantData variantData,
            @NonNull ReadOnlyObjectProvider readOnlyObjectProvider) {
        Class<? extends BaseVariantImpl> implementationClass =
                getVariantImplementationClass(variantData);
        if (implementationClass == null) {
            return null;
        }

        return objectFactory.newInstance(
                implementationClass,
                variantData,
                objectFactory,
                readOnlyObjectProvider,
                variantData
                        .getScope()
                        .getGlobalScope()
                        .getProject()
                        .container(VariantOutput.class));
    }

    @NonNull
    Collection<VariantType> getVariantConfigurationTypes();

    boolean hasTestScope();

    /**
     * Fail if the model is configured incorrectly.
     * @param model the non-null model to validate, as implemented by the VariantManager.
     * @throws org.gradle.api.GradleException when the model does not validate.
     */
    void validateModel(@NonNull VariantModel model);

    void preVariantWork(Project project);

    void createDefaultComponents(
            @NonNull NamedDomainObjectContainer<BuildType> buildTypes,
            @NonNull NamedDomainObjectContainer<ProductFlavor> productFlavors,
            @NonNull NamedDomainObjectContainer<SigningConfig> signingConfigs);
}
