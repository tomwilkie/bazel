// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.android;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.NativeClassObjectConstructor;
import com.google.devtools.build.lib.packages.SkylarkClassObject;

/**
 * A provider for targets that create Android instrumentations. Consumed by {@link
 * AndroidInstrumentationTest}.
 */
@Immutable
public class AndroidInstrumentationInfoProvider extends SkylarkClassObject
    implements TransitiveInfoProvider {

  private static final String SKYLARK_NAME = "AndroidInstrumentationInfo";
  static final NativeClassObjectConstructor ANDROID_INSTRUMENTATION_INFO =
      new NativeClassObjectConstructor(SKYLARK_NAME) {
      };

  private final Artifact targetApk;
  private final Artifact instrumentationApk;

  public AndroidInstrumentationInfoProvider(Artifact targetApk, Artifact instrumentationApk) {
    super(ANDROID_INSTRUMENTATION_INFO, ImmutableMap.<String, Object>of());
    this.targetApk = targetApk;
    this.instrumentationApk = instrumentationApk;
  }

  public Artifact getTargetApk() {
    return  targetApk;
  }

  public Artifact getInstrumentationApk() {
    return instrumentationApk;
  }
}
