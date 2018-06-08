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

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.http.client.utils.URIBuilder;

public abstract class EmJarTest
{
    public static final String WEIRD = "æøå😱 %&;*+`\"\\-weird";

    protected File getResourceFile(String name)
        throws URISyntaxException
    {
        final ClassLoader cl = getClass().getClassLoader();
        final URI base = cl.getResource("no/hassle/emjar/").toURI();
        URIBuilder builder = new URIBuilder(base);
        builder.setPath(builder.getPath() + name);
        final URI uri = builder.build();
        if (!"file".equals(uri.getScheme())) {
            throw new IllegalArgumentException(
                "Resource " + name + " not present as file (" + uri + ")");
        }
        return new File(uri.getSchemeSpecificPart());
    }
}
