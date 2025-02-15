/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.runtime.isolation;

import org.apache.kafka.common.config.provider.ConfigProvider;
import org.apache.kafka.connect.components.Versioned;
import org.apache.kafka.connect.connector.Connector;
import org.apache.kafka.connect.connector.policy.ConnectorClientConfigOverridePolicy;
import org.apache.kafka.connect.rest.ConnectRestExtension;
import org.apache.kafka.connect.sink.SinkConnector;
import org.apache.kafka.connect.source.SourceConnector;
import org.apache.kafka.connect.storage.Converter;
import org.apache.kafka.connect.storage.HeaderConverter;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.predicates.Predicate;
import org.reflections.Configuration;
import org.reflections.Reflections;
import org.reflections.ReflectionsException;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A custom classloader dedicated to loading Connect plugin classes in classloading isolation.
 *
 * <p>
 * Under the current scheme for classloading isolation in Connect, the delegating classloader loads
 * plugin classes that it finds in its child plugin classloaders. For classes that are not plugins,
 * this delegating classloader delegates its loading to its parent. This makes this classloader a
 * child-first classloader.
 * <p>
 * This class is thread-safe and parallel capable.
 */
public class DelegatingClassLoader extends URLClassLoader {
    private static final Logger log = LoggerFactory.getLogger(DelegatingClassLoader.class);
    public static final String UNDEFINED_VERSION = "undefined";

    private final ConcurrentMap<String, SortedMap<PluginDesc<?>, ClassLoader>> pluginLoaders;
    private final ConcurrentMap<String, String> aliases;
    private final SortedSet<PluginDesc<SinkConnector>> sinkConnectors;
    private final SortedSet<PluginDesc<SourceConnector>> sourceConnectors;
    private final SortedSet<PluginDesc<Converter>> converters;
    private final SortedSet<PluginDesc<HeaderConverter>> headerConverters;
    private final SortedSet<PluginDesc<Transformation<?>>> transformations;
    private final SortedSet<PluginDesc<Predicate<?>>> predicates;
    private final SortedSet<PluginDesc<ConfigProvider>> configProviders;
    private final SortedSet<PluginDesc<ConnectRestExtension>> restExtensions;
    private final SortedSet<PluginDesc<ConnectorClientConfigOverridePolicy>> connectorClientConfigPolicies;
    private final List<Path> pluginLocations;

    // Although this classloader does not load classes directly but rather delegates loading to a
    // PluginClassLoader or its parent through its base class, because of the use of inheritance in
    // in the latter case, this classloader needs to also be declared as parallel capable to use
    // fine-grain locking when loading classes.
    static {
        ClassLoader.registerAsParallelCapable();
    }

    public DelegatingClassLoader(List<Path> pluginLocations, ClassLoader parent) {
        super(new URL[0], parent);
        this.pluginLocations = pluginLocations;
        this.pluginLoaders = new ConcurrentHashMap<>();
        this.aliases = new ConcurrentHashMap<>();
        this.sinkConnectors = new TreeSet<>();
        this.sourceConnectors = new TreeSet<>();
        this.converters = new TreeSet<>();
        this.headerConverters = new TreeSet<>();
        this.transformations = new TreeSet<>();
        this.predicates = new TreeSet<>();
        this.configProviders = new TreeSet<>();
        this.restExtensions = new TreeSet<>();
        this.connectorClientConfigPolicies = new TreeSet<>();
    }

    public DelegatingClassLoader(List<Path> pluginLocations) {
        // Use as parent the classloader that loaded this class. In most cases this will be the
        // System classloader. But this choice here provides additional flexibility in managed
        // environments that control classloading differently (OSGi, Spring and others) and don't
        // depend on the System classloader to load Connect's classes.
        this(pluginLocations, DelegatingClassLoader.class.getClassLoader());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Set<PluginDesc<Connector>> connectors() {
        Set<PluginDesc<Connector>> connectors = new TreeSet<>((Set) sinkConnectors);
        connectors.addAll((Set) sourceConnectors);
        return connectors;
    }

    public Set<PluginDesc<SinkConnector>> sinkConnectors() {
        return sinkConnectors;
    }

    public Set<PluginDesc<SourceConnector>> sourceConnectors() {
        return sourceConnectors;
    }

    public Set<PluginDesc<Converter>> converters() {
        return converters;
    }

    public Set<PluginDesc<HeaderConverter>> headerConverters() {
        return headerConverters;
    }

    public Set<PluginDesc<Transformation<?>>> transformations() {
        return transformations;
    }

    public Set<PluginDesc<Predicate<?>>> predicates() {
        return predicates;
    }

    public Set<PluginDesc<ConfigProvider>> configProviders() {
        return configProviders;
    }

    public Set<PluginDesc<ConnectRestExtension>> restExtensions() {
        return restExtensions;
    }

    public Set<PluginDesc<ConnectorClientConfigOverridePolicy>> connectorClientConfigPolicies() {
        return connectorClientConfigPolicies;
    }

    /**
     * Retrieve the PluginClassLoader associated with a plugin class
     * @param name The fully qualified class name of the plugin
     * @return the PluginClassLoader that should be used to load this, or null if the plugin is not isolated.
     */
    // VisibleForTesting
    PluginClassLoader pluginClassLoader(String name) {
        if (!PluginUtils.shouldLoadInIsolation(name)) {
            return null;
        }
        SortedMap<PluginDesc<?>, ClassLoader> inner = pluginLoaders.get(name);
        if (inner == null) {
            return null;
        }
        ClassLoader pluginLoader = inner.get(inner.lastKey());
        return pluginLoader instanceof PluginClassLoader
               ? (PluginClassLoader) pluginLoader
               : null;
    }

    ClassLoader connectorLoader(String connectorClassOrAlias) {
        String fullName = aliases.getOrDefault(connectorClassOrAlias, connectorClassOrAlias);
        ClassLoader classLoader = pluginClassLoader(fullName);
        if (classLoader == null) classLoader = this;
        log.debug(
            "Getting plugin class loader: '{}' for connector: {}",
            classLoader,
            connectorClassOrAlias
        );
        return classLoader;
    }

    // VisibleForTesting
    PluginClassLoader newPluginClassLoader(
            final URL pluginLocation,
            final URL[] urls,
            final ClassLoader parent
    ) {
        return AccessController.doPrivileged(
                (PrivilegedAction<PluginClassLoader>) () -> new PluginClassLoader(pluginLocation, urls, parent)
        );
    }

    private <T> void addPlugins(Collection<PluginDesc<T>> plugins, ClassLoader loader) {
        for (PluginDesc<T> plugin : plugins) {
            String pluginClassName = plugin.className();
            SortedMap<PluginDesc<?>, ClassLoader> inner = pluginLoaders.get(pluginClassName);
            if (inner == null) {
                inner = new TreeMap<>();
                pluginLoaders.put(pluginClassName, inner);
                // TODO: once versioning is enabled this line should be moved outside this if branch
                log.info("Added plugin '{}'", pluginClassName);
            }
            inner.put(plugin, loader);
        }
    }

    protected void initLoaders() {
        for (Path pluginLocation : pluginLocations) {
            try {
                registerPlugin(pluginLocation);
            } catch (InvalidPathException | MalformedURLException e) {
                log.error("Invalid path in plugin path: {}. Ignoring.", pluginLocation, e);
            } catch (IOException e) {
                log.error("Could not get listing for plugin path: {}. Ignoring.", pluginLocation, e);
            }
        }
        // Finally add parent/system loader.
        scanUrlsAndAddPlugins(
                getParent(),
                ClasspathHelper.forJavaClassPath().toArray(new URL[0])
        );
        addAllAliases();
    }

    private void registerPlugin(Path pluginLocation)
        throws IOException {
        log.info("Loading plugin from: {}", pluginLocation);
        List<URL> pluginUrls = new ArrayList<>();
        for (Path path : PluginUtils.pluginUrls(pluginLocation)) {
            pluginUrls.add(path.toUri().toURL());
        }
        URL[] urls = pluginUrls.toArray(new URL[0]);
        if (log.isDebugEnabled()) {
            log.debug("Loading plugin urls: {}", Arrays.toString(urls));
        }
        PluginClassLoader loader = newPluginClassLoader(
                pluginLocation.toUri().toURL(),
                urls,
                this
        );
        scanUrlsAndAddPlugins(loader, urls);
    }

    private void scanUrlsAndAddPlugins(
            ClassLoader loader,
            URL[] urls
    ) {
        PluginScanResult plugins = scanPluginPath(loader, urls);
        log.info("Registered loader: {}", loader);
        if (!plugins.isEmpty()) {
            addPlugins(plugins.sinkConnectors(), loader);
            sinkConnectors.addAll(plugins.sinkConnectors());
            addPlugins(plugins.sourceConnectors(), loader);
            sourceConnectors.addAll(plugins.sourceConnectors());
            addPlugins(plugins.converters(), loader);
            converters.addAll(plugins.converters());
            addPlugins(plugins.headerConverters(), loader);
            headerConverters.addAll(plugins.headerConverters());
            addPlugins(plugins.transformations(), loader);
            transformations.addAll(plugins.transformations());
            addPlugins(plugins.predicates(), loader);
            predicates.addAll(plugins.predicates());
            addPlugins(plugins.configProviders(), loader);
            configProviders.addAll(plugins.configProviders());
            addPlugins(plugins.restExtensions(), loader);
            restExtensions.addAll(plugins.restExtensions());
            addPlugins(plugins.connectorClientConfigPolicies(), loader);
            connectorClientConfigPolicies.addAll(plugins.connectorClientConfigPolicies());
        }

        loadJdbcDrivers(loader);
    }

    private void loadJdbcDrivers(final ClassLoader loader) {
        // Apply here what java.sql.DriverManager does to discover and register classes
        // implementing the java.sql.Driver interface.
        AccessController.doPrivileged(
            (PrivilegedAction<Void>) () -> {
                ServiceLoader<Driver> loadedDrivers = ServiceLoader.load(
                        Driver.class,
                        loader
                );
                Iterator<Driver> driversIterator = loadedDrivers.iterator();
                try {
                    while (driversIterator.hasNext()) {
                        Driver driver = driversIterator.next();
                        log.debug(
                                "Registered java.sql.Driver: {} to java.sql.DriverManager",
                                driver
                        );
                    }
                } catch (Throwable t) {
                    log.debug(
                            "Ignoring java.sql.Driver classes listed in resources but not"
                                    + " present in class loader's classpath: ",
                            t
                    );
                }
                return null;
            }
        );
    }

    private PluginScanResult scanPluginPath(
            ClassLoader loader,
            URL[] urls
    ) {
        ConfigurationBuilder builder = new ConfigurationBuilder();
        builder.setClassLoaders(new ClassLoader[]{loader});
        builder.addUrls(urls);
        builder.setScanners(new SubTypesScanner());
        builder.useParallelExecutor();
        Reflections reflections = new InternalReflections(builder);

        return new PluginScanResult(
                getPluginDesc(reflections, SinkConnector.class, loader),
                getPluginDesc(reflections, SourceConnector.class, loader),
                getPluginDesc(reflections, Converter.class, loader),
                getPluginDesc(reflections, HeaderConverter.class, loader),
                getTransformationPluginDesc(loader, reflections),
                getPredicatePluginDesc(loader, reflections),
                getServiceLoaderPluginDesc(ConfigProvider.class, loader),
                getServiceLoaderPluginDesc(ConnectRestExtension.class, loader),
                getServiceLoaderPluginDesc(ConnectorClientConfigOverridePolicy.class, loader)
        );
    }

    @SuppressWarnings({"unchecked"})
    private Collection<PluginDesc<Predicate<?>>> getPredicatePluginDesc(ClassLoader loader, Reflections reflections) {
        return (Collection<PluginDesc<Predicate<?>>>) (Collection<?>) getPluginDesc(reflections, Predicate.class, loader);
    }

    @SuppressWarnings({"unchecked"})
    private Collection<PluginDesc<Transformation<?>>> getTransformationPluginDesc(ClassLoader loader, Reflections reflections) {
        return (Collection<PluginDesc<Transformation<?>>>) (Collection<?>) getPluginDesc(reflections, Transformation.class, loader);
    }

    private <T> Collection<PluginDesc<T>> getPluginDesc(
            Reflections reflections,
            Class<T> klass,
            ClassLoader loader
    ) {
        Set<Class<? extends T>> plugins;
        try {
            plugins = reflections.getSubTypesOf(klass);
        } catch (ReflectionsException e) {
            log.debug("Reflections scanner could not find any classes for URLs: " +
                    reflections.getConfiguration().getUrls(), e);
            return Collections.emptyList();
        }

        Collection<PluginDesc<T>> result = new ArrayList<>();
        for (Class<? extends T> pluginKlass : plugins) {
            if (!PluginUtils.isConcrete(pluginKlass)) {
                log.debug("Skipping {} as it is not concrete implementation", pluginKlass);
                continue;
            }
            if (pluginKlass.getClassLoader() != loader) {
                log.debug("{} from other classloader {} is visible from {}, excluding to prevent isolated loading",
                        pluginKlass.getSimpleName(), pluginKlass.getClassLoader(), loader);
                continue;
            }
            try (LoaderSwap loaderSwap = withClassLoader(loader)) {
                result.add(pluginDesc(pluginKlass, versionFor(pluginKlass), loader));
            } catch (ReflectiveOperationException | LinkageError e) {
                log.error("Failed to discover {}: Unable to instantiate {}{}", klass.getSimpleName(), pluginKlass.getSimpleName(), reflectiveErrorDescription(e), e);
            }
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> PluginDesc<T> pluginDesc(Class<? extends T> plugin, String version, ClassLoader loader) {
        return new PluginDesc(plugin, version, loader);
    }

    @SuppressWarnings("unchecked")
    private <T> Collection<PluginDesc<T>> getServiceLoaderPluginDesc(Class<T> klass, ClassLoader loader) {
        Collection<PluginDesc<T>> result = new ArrayList<>();
        ServiceLoader<T> serviceLoader = ServiceLoader.load(klass, loader);
        for (Iterator<T> iterator = serviceLoader.iterator(); iterator.hasNext(); ) {
            try (LoaderSwap loaderSwap = withClassLoader(loader)) {
                T pluginImpl;
                try {
                    pluginImpl = iterator.next();
                } catch (ServiceConfigurationError t) {
                    log.error("Failed to discover {}{}", klass.getSimpleName(), reflectiveErrorDescription(t.getCause()), t);
                    continue;
                }
                Class<? extends T> pluginKlass = (Class<? extends T>) pluginImpl.getClass();
                if (pluginKlass.getClassLoader() != loader) {
                    log.debug("{} from other classloader {} is visible from {}, excluding to prevent isolated loading",
                            pluginKlass.getSimpleName(), pluginKlass.getClassLoader(), loader);
                    continue;
                }
                result.add(pluginDesc(pluginKlass, versionFor(pluginImpl), loader));
            }
        }
        return result;
    }

    private static <T>  String versionFor(T pluginImpl) {
        try {
            if (pluginImpl instanceof Versioned) {
                return ((Versioned) pluginImpl).version();
            }
        } catch (Throwable t) {
            log.error("Failed to get plugin version for " + pluginImpl.getClass(), t);
        }
        return UNDEFINED_VERSION;
    }

    public static <T> String versionFor(Class<? extends T> pluginKlass) throws ReflectiveOperationException {
        // Temporary workaround until all the plugins are versioned.
        return Connector.class.isAssignableFrom(pluginKlass) ?
            versionFor(pluginKlass.getDeclaredConstructor().newInstance()) : UNDEFINED_VERSION;
    }

    private static String reflectiveErrorDescription(Throwable t) {
        if (t instanceof NoSuchMethodException) {
            return ": Plugin class must have a no-args constructor, and cannot be a non-static inner class";
        } else if (t instanceof SecurityException) {
            return ": Security settings must allow reflective instantiation of plugin classes";
        } else if (t instanceof IllegalAccessException) {
            return ": Plugin class default constructor must be public";
        } else if (t instanceof ExceptionInInitializerError) {
            return ": Failed to statically initialize plugin class";
        } else if (t instanceof InvocationTargetException) {
            return ": Failed to invoke plugin constructor";
        } else {
            return "";
        }
    }

    public LoaderSwap withClassLoader(ClassLoader loader) {
        ClassLoader savedLoader = Plugins.compareAndSwapLoaders(loader);
        try {
            return new LoaderSwap(savedLoader);
        } catch (Throwable t) {
            Plugins.compareAndSwapLoaders(savedLoader);
            throw t;
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        String fullName = aliases.getOrDefault(name, name);
        PluginClassLoader pluginLoader = pluginClassLoader(fullName);
        if (pluginLoader != null) {
            log.trace("Retrieving loaded class '{}' from '{}'", fullName, pluginLoader);
            return pluginLoader.loadClass(fullName, resolve);
        }

        return super.loadClass(fullName, resolve);
    }

    private void addAllAliases() {
        addAliases(connectors());
        addAliases(converters);
        addAliases(headerConverters);
        addAliases(transformations);
        addAliases(predicates);
        addAliases(restExtensions);
        addAliases(connectorClientConfigPolicies);
    }

    private <S> void addAliases(Collection<PluginDesc<S>> plugins) {
        for (PluginDesc<S> plugin : plugins) {
            if (PluginUtils.isAliasUnique(plugin, plugins)) {
                String simple = PluginUtils.simpleName(plugin);
                String pruned = PluginUtils.prunedName(plugin);
                aliases.put(simple, plugin.className());
                if (simple.equals(pruned)) {
                    log.info("Added alias '{}' to plugin '{}'", simple, plugin.className());
                } else {
                    aliases.put(pruned, plugin.className());
                    log.info(
                            "Added aliases '{}' and '{}' to plugin '{}'",
                            simple,
                            pruned,
                            plugin.className()
                    );
                }
            }
        }
    }

    private static class InternalReflections extends Reflections {

        public InternalReflections(Configuration configuration) {
            super(configuration);
        }

        // When Reflections is used for parallel scans, it has a bug where it propagates ReflectionsException
        // as RuntimeException.  Override the scan behavior to emulate the singled-threaded logic.
        @Override
        protected void scan(URL url) {
            try {
                super.scan(url);
            } catch (ReflectionsException e) {
                Logger log = Reflections.log;
                if (log != null && log.isWarnEnabled()) {
                    log.warn("could not create Vfs.Dir from url. ignoring the exception and continuing", e);
                }
            }
        }
    }
}
