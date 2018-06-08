/**
 * Copyright (C) 2014 Telenor Digital AS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package no.hassle.emjar;

import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.io.File;
import java.io.IOException;

/**
 * Class loader able to use embedded jars as part of the classpath.
 * All jars specified in the original classpath are opened and
 * inspected, any jar files found within will be added to the
 * classpath.  If the embedded jar files are stored using the ZIP
 * archiving method <em>stored</em> (i.e no compression), they will be
 * mapped directly and individual classes/elements can be loaded
 * on-demand.  For compressed embedded jars, initial access will
 * preload the contents of all contained elements.
 *
 * <p/>
 * The EmJar class loader can be invoked by setting the system
 * property <strong><code>java.system.class.loader</code></strong> to
 * the value
 * <strong><code>no.hassle.emjar.EmJarClassLoader</code></strong>,
 * e.g by using the <strong><code>-D</code></strong> flag to the
 * <strong><code>java</code></strong> executable.  (The emjar classes
 * must for obvious reasons be stored directly inside the bundle jar;
 * i.e not within an embedded jar.)
 *
 * <p/>
 * All jars given as part of the classpath will be recursively
 * inspected for embedded jars, and these will in turn be added to the
 * classpath.  Jar files on the classpath having manifests declaring
 * <strong><code>Premain-Class:</code></strong> (i.e Java Agent jars)
 * will not be inspected by default, but can be added to the
 * EmJar-specific classpath contained in the
 * <strong><code>emjar.class.path</code></strong> property if this is
 * desired.

 * <p/>
 * For a less manual approach that embeds all configuration in the
 * bundled jar, see {@link Boot}.
 *
 * <p/>
 * (To ensure that embedded jars are stored as-is and not compressed,
 * use e.g <strong><code>maven-assembly-plugin</code></strong> version
 * 2.4 or higher, and keep the
 * <strong><code>recompressZippedFiles</code></strong> configuration
 * option set to false (the default as of version 2.4)).
 *
 */
public class EmJarClassLoader
    extends URLClassLoader
{
    public final static String EMJAR_LOG_QUIET_PROP = "emjar.log.quiet";
    public final static String EMJAR_LOG_DEBUG_PROP = "emjar.log.debug";
    public final static String EMJAR_CLASS_PATH_PROP = "emjar.class.path";
    public final static String JAVA_CLASS_PATH_PROP = "java.class.path";

    protected static boolean DEBUG = false;
    protected static boolean QUIET = false;

    public final static String SEPARATOR = "!/";

    static {
        try {
            ClassLoader.registerAsParallelCapable();
        }
        catch (Throwable ignored) {
        }
    }

    private EmJarClassLoader(
            final Handler handler, final Properties props, final ClassLoader parent) {
        super(getClassPath(props, handler), parent, new HandlerFactory(handler));
    }

    public EmJarClassLoader()
    {
        this(new Handler(), System.getProperties(), null);
    }

    public EmJarClassLoader(final ClassLoader parent)
    {
        this(new Handler(), System.getProperties(), parent);
    }

    protected EmJarClassLoader(final Properties props)
    {
        this(new Handler(), props, null);
    }

    private static URL[] getClassPath(final Properties props, final Handler handler)
    {
        QUIET = "true".equalsIgnoreCase(props.getProperty(EMJAR_LOG_QUIET_PROP, ""));
        DEBUG = "true".equalsIgnoreCase(props.getProperty(EMJAR_LOG_DEBUG_PROP, ""));

        final ArrayList<URL> urls = new ArrayList<>();
        addClassPathUrls(props.getProperty(JAVA_CLASS_PATH_PROP), urls, handler, false);
        addClassPathUrls(props.getProperty(EMJAR_CLASS_PATH_PROP), urls, handler, true);
        if (DEBUG) {
            System.err.println("EmJar: using classpath " + urls);
        }
        return urls.toArray(new URL[0]);
    }

    private static void addClassPathUrls(
            final String classPath,
            final List<URL> urls,
            final Handler handler,
            final boolean force) {
        if (classPath == null) {
            return;
        }
        for (String elem : classPath.split(File.pathSeparator)) {
            final File file = new File(elem);
            try {
                urls.add(file.toURI().toURL());
                if (!file.isFile() || !file.getName().endsWith(".jar")) {
                    continue;
                }
                final JarFile jar = new JarFile(file);
                final Manifest mf = jar.getManifest();
                if (mf != null) {
                    if ((mf.getMainAttributes().getValue("Premain-Class") != null) && !force) {
                        if (DEBUG) {
                            System.err.println("EmJar: skipping java agent jar: " + elem);
                        }
                        continue;
                    }
                }
                final Enumeration<JarEntry> embedded = jar.entries();
                while (embedded.hasMoreElements()) {
                    final JarEntry entry = embedded.nextElement();
                    if (entry.getName().endsWith(".jar")) {
                        final URI nested = new URI(
                            "jar:file",
                            file.getAbsolutePath() + SEPARATOR + entry.getName(),
                            null);
                        urls.add(uriToUrl(nested, handler));
                    }
                }
                jar.close();
            }
            catch (IOException|URISyntaxException e) {
                if (!QUIET) {
                    System.err.println("EmJar: unable to process classpath entry " + elem);
                }
                if (DEBUG) {
                    e.printStackTrace(System.err);
                }
                // Trying to get by on the classpath entries we can process.
            }
        }
    }

    private static URL uriToUrl(URI uri, Handler handler)
        throws MalformedURLException
    {
        final URL url = uri.toURL();
        return new URL(
            url.getProtocol(),
            url.getHost(),
            url.getPort(),
            url.getFile(),
            handler);
    }

    private static class HandlerFactory
        implements URLStreamHandlerFactory
    {
        private final Handler handler;

        public HandlerFactory(final Handler handler) {
            this.handler = handler;
        }

        @Override
        public URLStreamHandler createURLStreamHandler(String protocol)
        {
            return "jar".equals(protocol) ? handler : null;
        }
    }

    private static class Handler
        extends URLStreamHandler
    {
        private final Map<String, JarURLConnection> connections
            = new ConcurrentHashMap<>();
        private final Map<String, Map<String, Map<String, OndemandEmbeddedJar.Descriptor>>> rootJars
            = new ConcurrentHashMap<>();

        @Override
        protected URLConnection openConnection(URL url)
            throws IOException
        {
            final URI bundle;
            final String path;
            try {
                final URI nested = url.toURI();
                if (!"jar".equals(nested.getScheme())) {
                    throw new IOException(
                        "Unexpected nested scheme passed to openConnection (expeced jar): "
                            + nested.getScheme());
                }
                bundle = new URI(nested.getRawSchemeSpecificPart());
                if ("jar".equals(bundle.getScheme())) {
                    final URI file = new URI(bundle.getRawSchemeSpecificPart());
                    if (!"file".equals(file.getScheme())) {
                        throw new IOException(
                            "Unexpected location scheme passed to openConnection (expected file): "
                                + file.getScheme());
                    }
                    path = file.getSchemeSpecificPart();
                }
                else {
                    if ("file".equals(bundle.getScheme())) {
                        path = bundle.getSchemeSpecificPart() + SEPARATOR;
                    }
                    else {
                        throw new IOException(
                            "Unexpected bundle scheme passed to openConnection (expected jar or file): "
                                + bundle.getScheme());
                    }
                }
            }
            catch (URISyntaxException e) {
                throw new IOException(e);
            }
            catch (IOException e) {
                if (DEBUG) {
                    System.err.println("EmJar: " + e.getMessage());
                }
                throw e;
            }
            if (DEBUG) {
                System.err.println("EmJar: loading " + path);
            }
            JarURLConnection conn = connections.get(path);
            if (conn == null) {
                synchronized (connections) {
                    conn = connections.get(path);
                    if (conn == null) {
                        final int i = path.indexOf(SEPARATOR);
                        final int j = path.indexOf(SEPARATOR, i + 1);
                        if (i < 0 || j < 0) {
                            throw new IOException("Unable to parse " + path);
                        }
                        final String root = path.substring(0, i);
                        final String nested = path.substring(i + SEPARATOR.length(), j);
                        final String entry = path.substring(j + SEPARATOR.length());
                        if (!nested.endsWith(".jar")) {
                            final URL urlDefaultHandler
                                = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile());
                            return urlDefaultHandler.openConnection();
                        }

                        Map<String, Map<String, OndemandEmbeddedJar.Descriptor>> rootJar
                            = rootJars.get(root);
                        if (rootJar == null) {
                            synchronized (rootJars) {
                                rootJar = rootJars.get(root);
                                if (rootJar == null) {
                                    final ZipScanner scanner
                                        = new ZipScanner(new File(root));
                                    rootJar = scanner.scan();
                                    rootJars.put(root, rootJar);
                                }
                            }
                        }
                        final Map<String, OndemandEmbeddedJar.Descriptor> descriptors
                            = rootJar.get(nested);
                        final URL rootUrl = new URL("jar:file:" + root + SEPARATOR);
                        if (descriptors != null) {
                            conn = new OndemandEmbeddedJar.Connection(
                                rootUrl, root, descriptors, entry);
                        }
                        else {
                            conn = new PreloadedEmbeddedJar.Connection(
                                rootUrl, root, nested, entry);
                        }
                        connections.put(path, conn);
                    }
                }
            }
            return conn;
        }
    }
}
