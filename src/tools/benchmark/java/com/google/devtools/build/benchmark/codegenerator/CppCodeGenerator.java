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

package com.google.devtools.build.benchmark.codegenerator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Create 4 types of Cpp project, or modify existing ones. */
public class CppCodeGenerator extends CodeGenerator {

  private static final int SIZE_A_FEW_FILES = 10;
  private static final int SIZE_MANY_FILES = 200;
  private static final int SIZE_LONG_CHAINED_DEPS = 20;
  private static final int SIZE_PARALLEL_DEPS = 20;

  private static final String DIR_SUFFIX = "/cpp";

  @Override
  public String getDirSuffix() {
    return DIR_SUFFIX;
  }

  @Override
  public int getSizeAFewFiles() {
    return SIZE_A_FEW_FILES;
  }

  @Override
  public int getSizeManyFiles() {
    return SIZE_MANY_FILES;
  }

  @Override
  public int getSizeLongChainedDeps() {
    return SIZE_LONG_CHAINED_DEPS;
  }

  @Override
  public int getSizeParallelDeps() {
    return SIZE_PARALLEL_DEPS;
  }

  /** Target type 1/2: Create targets with some files */
  @Override
  void createTargetWithSomeFiles(Path projectPath, int numberOfFiles) {
    if (pathExists(projectPath)) {
      return;
    }

    try {
      Files.createDirectories(projectPath);
      for (int i = 0; i < numberOfFiles; ++i) {
        CppCodeGeneratorHelper.createRandomClass("RandomClass" + i, projectPath);
      }
      CppCodeGeneratorHelper.writeBuildFileWithAllFilesToDir(
          projectPath.getFileName().toString(), projectPath);
    } catch (IOException e) {
      System.err.println("Error creating target with some files: " + e.getMessage());
    }
  }

  /** Target type 1/2: Modify targets with some files */
  @Override
  void modifyTargetWithSomeFiles(Path projectPath) {
    File dir = projectPath.toFile();
    if (directoryNotExists(dir)) {
      System.err.format(
          "Project dir (%s) does not contain code for modification.\n", projectPath.toString());
      return;
    }

    try {
      CppCodeGeneratorHelper.createRandomClassExtra("RandomClass0", projectPath);
    } catch (IOException e) {
      System.err.println("Error modifying targets some files: " + e.getMessage());
    }
  }

  /** Target type 3: Create targets with a few long chained dependencies (A -> B -> C -> … -> Z) */
  @Override
  void createTargetWithLongChainedDeps(Path projectPath) {
    if (pathExists(projectPath)) {
      return;
    }

    try {
      Files.createDirectories(projectPath);

      int count = SIZE_LONG_CHAINED_DEPS;

      // Call next one for 1..(count-2)
      for (int i = 1; i < count - 1; ++i) {
        CppCodeGeneratorHelper.createClassAndBuildFileWithDepsNext(i, projectPath);
      }
      // Don't call next one for (count-1)
      CppCodeGeneratorHelper.createRandomClass("Deps" + (count - 1), projectPath);
      CppCodeGeneratorHelper.appendTargetToBuildFile("Deps" + (count - 1), projectPath);

      // Main
      String deps = "    deps=[ ':Deps1' ],";
      CppCodeGeneratorHelper.createMainClassAndBuildFileWithDeps(
          TARGET_LONG_CHAINED_DEPS, deps, projectPath);
    } catch (IOException e) {
      System.err.println(
          "Error creating targets with a few long chained dependencies: " + e.getMessage());
    }
  }

  /** Target type 3: Modify targets with a few long chained dependencies (A -> B -> C -> … -> Z) */
  @Override
  void modifyTargetWithLongChainedDeps(Path projectPath) {
    File dir = projectPath.toFile();
    if (directoryNotExists(dir)) {
      System.err.format(
          "Project dir (%s) does not contain code for modification.\n", projectPath.toString());
      return;
    }

    try {
      CppCodeGeneratorHelper.createClassWithDepsNextExtra(
          (SIZE_LONG_CHAINED_DEPS + 1) >> 1, projectPath);
    } catch (IOException e) {
      System.err.println(
          "Error modifying targets with a few long chained dependencies: " + e.getMessage());
    }
  }

  /** Target type 4: Create targets with lots of parallel dependencies (A -> B, C, D, E, F, G, H) */
  @Override
  void createTargetWithParallelDeps(Path projectPath) {
    if (pathExists(projectPath)) {
      return;
    }

    try {
      Files.createDirectories(projectPath);

      int count = SIZE_PARALLEL_DEPS;

      // parallel dependencies B~Z
      for (int i = 1; i < count; ++i) {
        CppCodeGeneratorHelper.createRandomClass("Deps" + i, projectPath);
        CppCodeGeneratorHelper.appendTargetToBuildFile("Deps" + i, projectPath);
      }

      // A(Main)
      String deps = "    deps=[ ";
      for (int i = 1; i < count; ++i) {
        deps += "\":Deps" + i + "\", ";
      }
      deps += "],";
      CppCodeGeneratorHelper.createMainClassAndBuildFileWithDeps(
          TARGET_PARALLEL_DEPS, deps, projectPath);
    } catch (IOException e) {
      System.err.println(
          "Error creating targets with lots of parallel dependencies: " + e.getMessage());
    }
  }

  /** Target type 4: Modify targets with lots of parallel dependencies (A -> B, C, D, E, F, G, H) */
  @Override
  void modifyTargetWithParallelDeps(Path projectPath) {
    File dir = projectPath.toFile();
    if (directoryNotExists(dir)) {
      System.err.format(
          "Project dir (%s) does not contain code for modification.\n", projectPath.toString());
      return;
    }
    try {
      CppCodeGeneratorHelper.createRandomClassExtra("Deps1", projectPath);
    } catch (IOException e) {
      System.err.println(
          "Error creating targets with lots of parallel dependencies: " + e.getMessage());
    }
  }

  private static boolean pathExists(Path path) {
    File dir = path.toFile();
    if (dir.exists()) {
      System.err.println("File or directory exists, not rewriting it: " + path);
      return true;
    }

    return false;
  }

  private static boolean directoryNotExists(File file) {
    return !(file.exists() && file.isDirectory());
  }
}
