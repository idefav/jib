/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.maven;

import com.google.api.client.http.HttpStatusCodes;
import com.google.cloud.tools.jib.builder.BuildConfiguration;
import com.google.cloud.tools.jib.builder.BuildImageSteps;
import com.google.cloud.tools.jib.builder.BuildLogger;
import com.google.cloud.tools.jib.builder.SourceFilesConfiguration;
import com.google.cloud.tools.jib.cache.CacheMetadataCorruptedException;
import com.google.cloud.tools.jib.image.DuplicateLayerException;
import com.google.cloud.tools.jib.image.LayerCountMismatchException;
import com.google.cloud.tools.jib.image.LayerPropertyNotFoundException;
import com.google.cloud.tools.jib.registry.NonexistentDockerCredentialHelperException;
import com.google.cloud.tools.jib.registry.NonexistentServerUrlDockerCredentialHelperException;
import com.google.cloud.tools.jib.registry.RegistryAuthenticationFailedException;
import com.google.cloud.tools.jib.registry.RegistryException;
import com.google.cloud.tools.jib.registry.RegistryUnauthorizedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/** Builds a container image. */
@Mojo(name = "build", requiresDependencyResolution = ResolutionScope.RUNTIME_PLUS_SYSTEM)
public class BuildImageMojo extends AbstractMojo {

  /** Directory name for the cache. The directory will be relative to the build output directory. */
  private static final String CACHE_DIRECTORY_NAME = "jib-cache";

  @Parameter(defaultValue = "${project}", readonly = true)
  private MavenProject project;

  // TODO: Replace the separate base image parameters with this.
  @Parameter(defaultValue = "gcr.io/distroless/java", required = true)
  private String from;

  // TODO: Remove these in favor of "from".
  @Parameter(defaultValue = "gcr.io", required = true)
  private String baseImageRegistry;

  @Parameter(defaultValue = "distroless/java", required = true)
  private String baseImageRepository;

  @Parameter(defaultValue = "latest", required = true)
  private String baseImageTag;

  @Parameter(required = true)
  private String registry;

  @Parameter(required = true)
  private String repository;

  @Parameter(defaultValue = "latest", required = true)
  private String tag;

  @Parameter private String credentialHelperName;

  @Parameter private List<String> jvmFlags;

  @Parameter private Map<String, String> environment;

  @Parameter private String mainClass;

  @Override
  public void execute() throws MojoExecutionException {
    if (mainClass == null) {
      Plugin mavenJarPlugin = project.getPlugin("org.apache.maven.plugins:maven-jar-plugin");
      if (mavenJarPlugin != null) {
        mainClass = getMainClassFromMavenJarPlugin(mavenJarPlugin);
        if (mainClass == null) {
          throw new MojoExceptionBuilder(
                  new MojoFailureException(
                      "Could not find main class specified in maven-jar-plugin"))
              .suggest("add a `mainClass` configuration to jib-maven-plugin")
              .build();
        }

        getLog().info("Using main class from maven-jar-plugin: " + mainClass);
      }
    }

    SourceFilesConfiguration sourceFilesConfiguration = getSourceFilesConfiguration();

    BuildConfiguration buildConfiguration =
        BuildConfiguration.builder()
            .setBuildLogger(
                new BuildLogger() {
                  @Override
                  public void debug(CharSequence charSequence) {
                    getLog().debug(charSequence);
                  }

                  @Override
                  public void info(CharSequence charSequence) {
                    getLog().info(charSequence);
                  }

                  @Override
                  public void warn(CharSequence charSequence) {
                    getLog().warn(charSequence);
                  }

                  @Override
                  public void error(CharSequence charSequence) {
                    getLog().error(charSequence);
                  }
                })
            .setBaseImageServerUrl(baseImageRegistry)
            .setBaseImageName(baseImageRepository)
            .setBaseImageTag(baseImageTag)
            .setTargetServerUrl(registry)
            .setTargetImageName(repository)
            .setTargetTag(tag)
            .setCredentialHelperName(credentialHelperName)
            .setMainClass(mainClass)
            .setJvmFlags(jvmFlags)
            .setEnvironment(environment)
            .build();

    Path cacheDirectory = Paths.get(project.getBuild().getDirectory(), CACHE_DIRECTORY_NAME);
    if (!Files.exists(cacheDirectory)) {
      try {
        Files.createDirectory(cacheDirectory);

      } catch (IOException ex) {
        throw new MojoExecutionException("Could not create cache directory: " + cacheDirectory, ex);
      }
    }

    try {
      BuildImageSteps buildImageSteps =
          new BuildImageSteps(buildConfiguration, sourceFilesConfiguration, cacheDirectory);
      buildImageSteps.runAsync();

    } catch (RegistryUnauthorizedException ex) {
      handleRegistryUnauthorizedException(ex);

    } catch (IOException
        | RegistryException
        | CacheMetadataCorruptedException
        | DuplicateLayerException
        | LayerPropertyNotFoundException
        | LayerCountMismatchException
        | NonexistentDockerCredentialHelperException
        | RegistryAuthenticationFailedException
        | NonexistentServerUrlDockerCredentialHelperException ex) {
      // TODO: Add more suggestions for various build failures.
      throw new MojoExceptionBuilder(ex).build();

    } catch (Exception ex) {
      throw new MojoExceptionBuilder(ex).suggest("WTF").build();
    }
  }

  private SourceFilesConfiguration getSourceFilesConfiguration() throws MojoExecutionException {
    try {
      SourceFilesConfiguration sourceFilesConfiguration =
          new MavenSourceFilesConfiguration(project);

      // Logs the different source files used.
      getLog().info("Dependencies:");
      sourceFilesConfiguration
          .getDependenciesFiles()
          .forEach(dependencyFile -> getLog().info("Dependency: " + dependencyFile));

      getLog().info("Resources:");
      sourceFilesConfiguration
          .getResourcesFiles()
          .forEach(resourceFile -> getLog().info("Resource: " + resourceFile));

      getLog().info("Classes:");
      sourceFilesConfiguration
          .getClassesFiles()
          .forEach(classesFile -> getLog().info("Class: " + classesFile));

      return sourceFilesConfiguration;

    } catch (IOException ex) {
      throw new MojoExecutionException("Obtaining project build output files failed", ex);
    }
  }

  @Nullable
  private String getMainClassFromMavenJarPlugin(Plugin mavenJarPlugin) {
    Xpp3Dom jarConfiguration = (Xpp3Dom) mavenJarPlugin.getConfiguration();
    if (jarConfiguration == null) {
      return null;
    }
    Xpp3Dom archiveObject = jarConfiguration.getChild("archive");
    if (archiveObject == null) {
      return null;
    }
    Xpp3Dom manifestObject = archiveObject.getChild("manifest");
    if (manifestObject == null) {
      return null;
    }
    Xpp3Dom mainClassObject = manifestObject.getChild("mainClass");
    if (mainClassObject == null) {
      return null;
    }
    return mainClassObject.getValue();
  }

  private void handleRegistryUnauthorizedException(RegistryUnauthorizedException ex)
      throws MojoExecutionException {
    MojoExceptionBuilder mojoExceptionBuilder = new MojoExceptionBuilder(ex);

    if (ex.getHttpResponseException().getStatusCode() == HttpStatusCodes.STATUS_CODE_FORBIDDEN) {
      // No permissions to push to target image.
      String targetImage = registry + "/" + repository + ":" + tag;
      mojoExceptionBuilder.suggest("make sure your have permission to push to " + targetImage);

    } else if (credentialHelperName == null) {
      // Credential helper not defined.
      mojoExceptionBuilder.suggest("set the configuration 'credentialHelperName'");

    } else {
      // Credential helper probably was not configured correctly or did not have the necessary
      // credentials.
      mojoExceptionBuilder.suggest("make sure your credential helper is set up correctly");
    }

    throw mojoExceptionBuilder.build();
  }
}
