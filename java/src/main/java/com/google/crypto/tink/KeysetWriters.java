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

import com.google.crypto.tink.proto.EncryptedKeyset;
import com.google.crypto.tink.proto.Keyset;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * This class consists exclusively of static methods that create new writers that can write
 * keysets in proto binary format to common storage systems.
 */
public final class KeysetWriters {

  public static KeysetWriter withOutputStream(OutputStream stream) {
    return new OutputStreamKeysetWriter(stream);
  }

  public static KeysetWriter withFile(File file) throws IOException {
    return new OutputStreamKeysetWriter(new FileOutputStream(file));
  }

  private static class OutputStreamKeysetWriter implements KeysetWriter {
    private final OutputStream outputStream;

    public OutputStreamKeysetWriter(OutputStream stream) {
      outputStream = stream;
    }

    @Override
    public void write(Keyset keyset) throws IOException {
      outputStream.write(keyset.toByteArray());
    }

    @Override
    public void write(EncryptedKeyset keyset) throws IOException {
      outputStream.write(keyset.toByteArray());
    }
  }
}
