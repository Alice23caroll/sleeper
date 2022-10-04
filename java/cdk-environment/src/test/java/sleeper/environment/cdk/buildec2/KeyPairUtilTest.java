/*
 * Copyright 2022 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sleeper.environment.cdk.buildec2;

import org.apache.commons.io.IOUtils;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

public class KeyPairUtilTest {

    @Test
    public void canGenerateKeyPairAndReconstructFromPem() throws Exception {
        KeyPair pair = KeyPairUtil.generate();
        String pem = KeyPairUtil.privatePem(pair);
        KeyPair found = KeyPairUtil.readPrivatePem(pem);
        assertThat(found.getPublic().getEncoded()).isEqualTo(pair.getPublic().getEncoded());
        assertThat(found.getPrivate().getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
    }

    @Test
    public void canGetPublicKeyInBase64() throws Exception {
        assertThat(KeyPairUtil.publicBase64(exampleKeyPair("examples/private.pem")))
                .isEqualTo(exampleString("examples/public.base64"));
    }

    @Test
    public void canBuildPemStringFromKeyPair() throws Exception {
        assertThat(KeyPairUtil.privatePem(exampleKeyPair("examples/private.pem")))
                .isEqualTo(exampleString("examples/private.pem"));
    }

    @Test
    public void canWritePrivateKeyFile() throws Exception {
        Path expectedPath = pathWithNoFile("WriteKey.pem");
        try {
            KeyPairUtil.writePrivateToFile(exampleKeyPair("examples/private.pem"),
                    "WriteKey.pem");
            assertThat(Files.getPosixFilePermissions(expectedPath))
                    .containsExactly(PosixFilePermission.OWNER_READ);
        } finally {
            Files.deleteIfExists(expectedPath);
        }
    }

    @Test
    public void canOverwritePrivateKeyFile() throws Exception {
        Path path = pathWithNoFile("OverwriteKey.pem");
        Files.createFile(path);
        try {
            KeyPairUtil.writePrivateToFile(exampleKeyPair("examples/private.pem"),
                    "OverwriteKey.pem");
            assertThat(Files.getPosixFilePermissions(path))
                    .containsExactly(PosixFilePermission.OWNER_READ);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static Path pathWithNoFile(String fileName) throws IOException {
        Path path = Paths.get(fileName);
        Files.deleteIfExists(path);
        return path;
    }

    private static KeyPair exampleKeyPair(String path) throws IOException {
        try (InputStream is = exampleResource(path).openStream()) {
            return KeyPairUtil.readPrivatePem(new InputStreamReader(is));
        }
    }

    private static String exampleString(String path) throws IOException {
        return IOUtils.toString(exampleResource(path), Charset.forName("UTF-8"));
    }

    private static URL exampleResource(String path) {
        return KeyPairUtilTest.class.getClassLoader().getResource(path);
    }
}
