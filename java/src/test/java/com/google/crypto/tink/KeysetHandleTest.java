// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.google.crypto.tink;

import static com.google.crypto.tink.TestUtil.assertExceptionContains;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.AeadKeyTemplates;
import com.google.crypto.tink.mac.MacConfig;
import com.google.crypto.tink.mac.MacKeyTemplates;
import com.google.crypto.tink.proto.EcdsaPrivateKey;
import com.google.crypto.tink.proto.KeyData;
import com.google.crypto.tink.proto.KeyStatusType;
import com.google.crypto.tink.proto.KeyTemplate;
import com.google.crypto.tink.proto.Keyset;
import com.google.crypto.tink.proto.OutputPrefixType;
import com.google.crypto.tink.signature.EcdsaVerifyKeyManager;
import com.google.crypto.tink.signature.PublicKeySignConfig;
import com.google.crypto.tink.signature.PublicKeySignFactory;
import com.google.crypto.tink.signature.PublicKeyVerifyConfig;
import com.google.crypto.tink.signature.PublicKeyVerifyFactory;
import com.google.crypto.tink.signature.SignatureKeyTemplates;
import com.google.crypto.tink.subtle.Random;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for KeysetHandle.
 */
@RunWith(JUnit4.class)
public class KeysetHandleTest {
  @Before
  public void setUp() throws GeneralSecurityException {
    AeadConfig.registerStandardKeyTypes();
    MacConfig.registerStandardKeyTypes();
    PublicKeyVerifyConfig.registerStandardKeyTypes();
    PublicKeySignConfig.registerStandardKeyTypes();
  }

  /**
   * Tests that toString doesn't contain key material.
   */
  @Test
  public void testToString() throws Exception {
    String keyValue = "01234567890123456";
    Keyset keyset = TestUtil.createKeyset(TestUtil.createKey(
        TestUtil.createHmacKeyData(keyValue.getBytes("UTF-8"), 16),
        42,
        KeyStatusType.ENABLED,
        OutputPrefixType.TINK));
    KeysetHandle handle = KeysetHandle.fromKeyset(keyset);
    assertEquals(keyset, handle.getKeyset());

    String keysetInfo = handle.toString();
    assertFalse(keysetInfo.contains(keyValue));
    assertTrue(handle.getKeyset().toString().contains(keyValue));
  }

  @Test
  public void testWriteEncrypted() throws Exception {
    KeysetHandle handle = KeysetHandle
        .generateNew(MacKeyTemplates.HMAC_SHA256_128BITTAG);
    // Encrypt the keyset with an AeadKey.
    KeyTemplate masterKeyTemplate = AeadKeyTemplates.AES128_GCM;
    Aead masterKey = Registry.INSTANCE.getPrimitive(
        Registry.INSTANCE.newKeyData(masterKeyTemplate));
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    KeysetWriter writer = KeysetWriters.withOutputStream(outputStream);
    handle.write(writer, masterKey);
    ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
    KeysetReader reader = KeysetReaders.withInputStream(inputStream);
    KeysetHandle handle2 = KeysetHandle.read(reader, masterKey);
    assertEquals(handle.getKeyset(), handle2.getKeyset());
  }

 /**
   * Tests a public keyset is extracted properly from a private keyset.
   */
  @Test
  public void testGetPublicKeysetHandle() throws Exception {
    KeysetHandle privateHandle = KeysetHandle.generateNew(
        SignatureKeyTemplates.ECDSA_P256);
    KeyData privateKeyData = privateHandle.getKeyset().getKey(0).getKeyData();
    EcdsaPrivateKey privateKey = EcdsaPrivateKey.parseFrom(privateKeyData.getValue());
    KeysetHandle publicHandle = privateHandle.getPublicKeysetHandle();
    assertEquals(1, publicHandle.getKeyset().getKeyCount());
    assertEquals(privateHandle.getKeyset().getPrimaryKeyId(),
        publicHandle.getKeyset().getPrimaryKeyId());
    KeyData publicKeyData = publicHandle.getKeyset().getKey(0).getKeyData();
    assertEquals(EcdsaVerifyKeyManager.TYPE_URL, publicKeyData.getTypeUrl());
    assertEquals(KeyData.KeyMaterialType.ASYMMETRIC_PUBLIC, publicKeyData.getKeyMaterialType());
    assertArrayEquals(privateKey.getPublicKey().toByteArray(),
        publicKeyData.getValue().toByteArray());

    PublicKeySign signer = PublicKeySignFactory.getPrimitive(privateHandle);
    PublicKeyVerify verifier = PublicKeyVerifyFactory.getPrimitive(publicHandle);
    byte[] message = Random.randBytes(20);
    try {
      verifier.verify(signer.sign(message), message);
    } catch (GeneralSecurityException e) {
      fail("Should not fail: " + e);
    }
  }

  /**
   * Tests that when encryption failed an exception is thrown.
   */
  @Test
  public void testEncryptFailed() throws Exception {
    KeysetHandle handle = KeysetManager.withEmptyKeyset()
        .rotate(MacKeyTemplates.HMAC_SHA256_128BITTAG)
        .getKeysetHandle();
    // Encrypt with dummy Aead.
    TestUtil.DummyAead faultyAead = new TestUtil.DummyAead();
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    KeysetWriter writer = KeysetWriters.withOutputStream(outputStream);
    try {
      handle.write(writer, faultyAead);
      fail("Expected GeneralSecurityException");
    } catch (GeneralSecurityException e) {
      assertExceptionContains(e, "dummy");
    }
  }

  @Test
  public void testVoidInputs() throws Exception {
    KeysetHandle unused;
    try {
      KeysetReader reader = KeysetReaders.withBytes(new byte[0]);
      unused = KeysetHandle.read(reader, null /* masterKey */);
      fail("Expected GeneralSecurityException");
    } catch (GeneralSecurityException e) {
      assertExceptionContains(e, "empty keyset");
    }
  }
}
