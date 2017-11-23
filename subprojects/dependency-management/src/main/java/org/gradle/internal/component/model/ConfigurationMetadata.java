/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.internal.component.model;

import org.gradle.api.attributes.HasAttributes;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.ModuleExclusion;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.ModuleExclusions;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.internal.DisplayName;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ConfigurationMetadata extends HasAttributes {
    /**
     * The set of configurations that this configuration extends. Includes this configuration.
     */
    // TODO:DAZ Remove this from this API: it's only required by implementors, not consumers
    Collection<String> getHierarchy();

    String getName();

    DisplayName asDescribable();

    /**
     * Attributes are immutable on ConfigurationMetadata
     */
    @Override
    ImmutableAttributes getAttributes();

    /**
     * Returns the dependencies that apply to this configuration.
     *
     * If the implementation supports {@link DependencyMetadataRules}, this method
     * is responsible for lazily applying the rules the first time it is called.
     */
    List<? extends DependencyMetadata> getDependencies();

    /**
     * Returns the artifacts associated with this configuration, if known.
     */
    List<? extends ComponentArtifactMetadata> getArtifacts();

    /**
     * Returns the variants of this configuration. Should include at least one value. Exactly one variant must be selected and the artifacts of that variant used.
     */
    Set<? extends VariantMetadata> getVariants();

    /**
     * Returns the exclusions to apply to outgoing dependencies from this configuration.
     * @param moduleExclusions the module exclusions factory
     */
    ModuleExclusion getExclusions(ModuleExclusions moduleExclusions);

    boolean isTransitive();

    boolean isVisible();

    boolean isCanBeConsumed();

    boolean isCanBeResolved();

    /**
     * Find the component artifact with the given IvyArtifactName, creating a new one if none matches.
     *
     * This is used to create a ComponentArtifactMetadata from an artifact declared as part of a dependency.
     * The reason to do this lookup is that for a local component artifact, the file is part of the artifact metadata.
     * (For external module components, we just instantiate a new artifact metadata).
     */
    // TODO:DAZ Try to remove this from the ConfigurationMetadata API
    ComponentArtifactMetadata artifact(IvyArtifactName artifact);
}
