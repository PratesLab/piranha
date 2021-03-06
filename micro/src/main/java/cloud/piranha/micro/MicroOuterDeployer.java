/*
 * Copyright (c) 2002-2020 Manorrock.com. All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *   1. Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 *   2. Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *   3. Neither the name of the copyright holder nor the names of its
 *      contributors may be used to endorse or promote products derived from
 *      this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package cloud.piranha.micro;

import static java.util.logging.Level.WARNING;
import static java.util.stream.Collectors.toList;
import static org.jboss.shrinkwrap.resolver.api.maven.repository.MavenUpdatePolicy.UPDATE_POLICY_NEVER;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.jboss.jandex.Index;
import org.jboss.jandex.IndexWriter;
import org.jboss.jandex.Indexer;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.importer.ZipImporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.resolver.api.maven.ConfigurableMavenResolverSystem;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenRemoteRepositories;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenRemoteRepository;

import cloud.piranha.resource.DefaultResourceManager;
import cloud.piranha.resource.shrinkwrap.IsolatingResourceManagerClassLoader;
import cloud.piranha.resource.shrinkwrap.ShrinkWrapResource;

/**
 * The micro outer deployer runs in the outer (or initial) class loader, and initializes the inner (isolated)
 * class loader.
 *
 * <p>
 * Initialization consists of loading the required classes that make up the requested configuration of Piranha Micro itself,
 * and putting these in the parent inner class loader, as well as indexing the application classes and putting both this index
 * and the application classes + resources in a child of the parent inner class loader.
 *
 * <p>
 * These inner class loaders are then used to bootstrap the inner deployer, and control is handed to it. The inner deployer
 * full runs in the inner class loader, and will startup an actual Piranha instance and deploy the given archive to it.
 *
 * @author Arjan Tijms
 *
 */
public class MicroOuterDeployer {

    private static final Logger LOGGER = Logger.getLogger(MicroOuterDeployer.class.getName());

    private final MicroConfiguration configuration;
    private Object microInnerDeployer;

    public MicroOuterDeployer() {
        this(new MicroConfiguration().postConstruct());
    }

    public MicroOuterDeployer(MicroConfiguration configuration) {
        this.configuration = configuration;
    }

    @SuppressWarnings("unchecked")
    public Set<String> deploy(Archive<?> archive) {
        Set<String> servletNames = new HashSet<>();

        if (!archive.contains("WEB-INF/beans.xml")) {
            archive.add(EmptyAsset.INSTANCE, "WEB-INF/beans.xml");
        }

        ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
        try {

            // Resolve all the dependencies that make up a Piranha runtime configuration

            ConfigurableMavenResolverSystem mavenResolver = Maven.configureResolver();

            configuration.getRepositoriesList().stream().forEach(repoUrl ->
                mavenResolver.withRemoteRepo(createRepo(repoUrl)));

            JavaArchive[] piranhaArchives =
                mavenResolver
                     .workOffline(configuration.isOffline())
                     .resolve(configuration.getMergedDependencies())
                     .withTransitivity()
                     .as(JavaArchive.class);

            // Make all those dependencies available to the Piranha class loader
            ClassLoader piranhaClassLoader = getPiranhaClassLoader(piranhaArchives);

            // Make the web application archive (the .war) available to a separate classloader
            // The webInfClassLoader delegates to the Piranha class loader.

            // The class loading hierarchy looks as follows:

            // Web-inf class loader (application classes)
            //        |
            //        |--- System class loader (Pass-through for Shrinkwrap classes only)
            //        |--- java.lang.ClassLoader (super class, Weld, Javasist etc hack-in their classes here)
            //        |
            // Piranha class loader (Piranha classes)
            //        |
            //        |
            // Platform class loader (JDK classes)

            ClassLoader webInfClassLoader = getWebInfClassLoader(archive, piranhaClassLoader);

            Thread.currentThread().setContextClassLoader(webInfClassLoader);

            try {
                URL.setURLStreamHandlerFactory(new StaticURLStreamHandlerFactory());
            } catch (Error error) { // Yes, we know...
                // Ignore
            }

            System.setProperty("micro.version", getClass().getPackage().getImplementationVersion());
            if (configuration.getRoot() != null) {
                System.setProperty("micro.root", configuration.getRoot());
            }

            microInnerDeployer =
                Class.forName(
                        "cloud.piranha.micro.MicroInnerDeployer",
                        true,
                        webInfClassLoader)
                     .getDeclaredConstructor()
                     .newInstance();

            servletNames.addAll((Set<String>)
                microInnerDeployer
                    .getClass()
                    .getMethod("start", Archive.class, ClassLoader.class, Map.class, Integer.class)
                    .invoke(microInnerDeployer,
                        archive,
                               webInfClassLoader,
                               StaticURLStreamHandlerFactory.getHandlers(),
                               configuration.getPort()));

            return servletNames;

        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
            throw new IllegalStateException("", e);
        } finally {
            Thread.currentThread().setContextClassLoader(oldClassLoader);
        }
    }

    public void stop() {
        if (microInnerDeployer != null) {
            try {
                microInnerDeployer
                    .getClass()
                    .getMethod("stop")
                    .invoke(microInnerDeployer);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
                if (LOGGER.isLoggable(WARNING)) {
                    LOGGER.log(WARNING, "Error occurred during stop of Piranha Micro", e);
                }
            }
        }
    }

    /**
     * Gets a class loader that provides access to the Piranha classes.
     *
     * @param piranhaArchives the archives containing code that makes up Piranha
     * @return A class loader giving access to the Piranha runtime code
     */
    ClassLoader getPiranhaClassLoader(Archive<?>[] piranhaArchives) {
        DefaultResourceManager manager = new DefaultResourceManager();

        for (Archive<?> archive : piranhaArchives) {
            manager.addResource(new ShrinkWrapResource(archive));
        }

        IsolatingResourceManagerClassLoader classLoader = new IsolatingResourceManagerClassLoader("Piranha Loader");
        classLoader.setResourceManager(manager);

        return classLoader;
    }

    /**
     * Gets a class loader that provides access to the classes in WEB-INF of a web application archive.
     *
     * @param applicationArchive the web application archive to provide access to
     * @param piranhaClassloader the parent class loader containing the Piranha runtime classes
     * @return A class loader giving access to the application code
     */
    ClassLoader getWebInfClassLoader(Archive<?> applicationArchive, ClassLoader piranhaClassloader) {
        // Create the resource that holds all classes from the WEB-INF/classes folder
        ShrinkWrapResource applicationResource = new ShrinkWrapResource("/WEB-INF/classes", applicationArchive);

        // Create the resources that hold all classes from the WEB-INF/lib folder.
        // Each resource holds the classes from a single jar
        ShrinkWrapResource jarResources = new ShrinkWrapResource("/WEB-INF/lib", applicationArchive);
        List<ShrinkWrapResource> webLibResources =
            jarResources.getAllLocations()
                        .filter(location -> location.endsWith(".jar"))
                        .map(location -> importAsShrinkWrapResource(jarResources, location))
                        .collect(toList());

        // Create a separate archive that contains an index of the application archive and the library archives.
        // This index can be obtained from the class loader by getting the "META-INF/piranha.idx" resource.
        ShrinkWrapResource indexResource = new ShrinkWrapResource(
            ShrinkWrap.create(JavaArchive.class)
                      .add(new ByteArrayAsset(createIndex(applicationResource, webLibResources)), "META-INF/piranha.idx"));


        IsolatingResourceManagerClassLoader classLoader = new IsolatingResourceManagerClassLoader(piranhaClassloader, "WebInf Loader");

        // Add the resources representing the application archive and index archive to the resource manager
        DefaultResourceManager manager = new DefaultResourceManager();
        manager.addResource(applicationResource);
        for (ShrinkWrapResource webLibResource : webLibResources) {
            manager.addResource(webLibResource);
        }
        manager.addResource(indexResource);

        // Make the application and library classes, as well as the index available to the class loader by setting the resource manager
        // that contains these.
        classLoader.setResourceManager(manager);

        return classLoader;
    }

    /**
     * Helper method that gets and imports a ZipFileEntry resource from a ShrinkWrapResource as
     * another ShrinkWrapResource.
     *
     * @param resource the ShrinkWrapResource used as the source
     * @param location the location of the target resource within the resource
     * @return a ShrinkWrapResource version of the target resource
     */
    private ShrinkWrapResource importAsShrinkWrapResource(ShrinkWrapResource resource, String location) {
        return new ShrinkWrapResource(
            ShrinkWrap.create(ZipImporter.class, location.substring(1))
                      .importFrom(
                          resource.getResourceAsStreamByLocation(location))
                      .as(JavaArchive.class));
    }

    private byte[] createIndex(ShrinkWrapResource applicationResource, List<ShrinkWrapResource> libResources) {
        Indexer indexer = new Indexer();

        // Add all classes from the library resources (the jar files in WEB-INF/lib)
        libResources
            .stream()
            .forEach(libResource ->
                libResource.getAllLocations()
                           .filter(e -> e.endsWith(".class"))
                           .forEach(className -> addToIndex(className, libResource, indexer)));


        // Add all classes from the application resource (the class files in WEB-INF/classes to the indexer)
        // Note this must be done last as according to the Servlet spec, WEB-INF/classes overrides WEB-INF/lib)
        applicationResource
            .getAllLocations()
            .filter(e -> e.endsWith(".class"))
            .forEach(className -> addToIndex(className, applicationResource, indexer));


        Index index = indexer.complete();

        // Write the index out to a byte array

        ByteArrayOutputStream indexBytes = new ByteArrayOutputStream();

        IndexWriter writer = new IndexWriter(indexBytes);

        try {
            writer.write(index);
        } catch (IOException ioe) {
            if (LOGGER.isLoggable(WARNING)) {
                LOGGER.log(WARNING, "Unable to write out index", ioe);
            }
        }

        return indexBytes.toByteArray();
    }

    private void addToIndex(String className, ShrinkWrapResource resource, Indexer indexer) {
        try (InputStream classAsStream = resource.getResourceAsStream(className)) {
            indexer.index(classAsStream);
        } catch (IOException ioe) {
            if (LOGGER.isLoggable(WARNING)) {
                LOGGER.log(WARNING, "Unable to add to index", ioe);
            }
        }
    }

    private MavenRemoteRepository createRepo(String repoUrl) {
        MavenRemoteRepository repo = MavenRemoteRepositories.createRemoteRepository(
            UUID.randomUUID().toString(), repoUrl, "default");

        repo.setUpdatePolicy(UPDATE_POLICY_NEVER);

        return repo;
    }

}
