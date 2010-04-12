/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

package jetbrains.buildServer.buildTriggers.vcs.clearcase;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.util.io.FileUtil;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.ParseException;

import org.apache.log4j.Logger;

import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsSupportUtil;
import jetbrains.buildServer.vcs.patches.PatchBuilder;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.versionTree.Version;

public class CCPatchProvider {

	private static final Logger LOG = Logger.getLogger(CCPatchProvider.class);	
	
  private File myTempFile;
  private final ClearCaseConnection myConnection;
  private static final boolean CC_OPTIMIZE_CHECKOUT = TeamCityProperties.getBoolean("clearcase.optimize.initial.checkout");
  private static final String EXECUTABLE_ATTR = "ugo+x";
  private final boolean myUseCCCache;

  public CCPatchProvider(ClearCaseConnection connection, final boolean useCcCache) {
    myConnection = connection;
    myUseCCCache = useCcCache;
  }

  public void buildPatch(final PatchBuilder builder, String fromVersion, String lastVersion)
    throws IOException, VcsException, ExecutionException, ParseException {
    try {
      if (fromVersion == null) {
        if (CC_OPTIMIZE_CHECKOUT) {
          VcsSupportUtil.exportFilesFromDisk(builder, new File(myConnection.getViewWholePath()));
        }
        else {
          myConnection.processAllVersions(lastVersion, createFileProcessor(builder), false, myUseCCCache);
        }
      }
      else if (!myConnection.isConfigSpecWasChanged()) {
        myConnection.prepare(lastVersion);
        CCParseUtil.processChangedFiles(myConnection, fromVersion, lastVersion, new ChangedFilesProcessor() {
            public void processChangedFile(final HistoryElement element) throws VcsException {
                final String path = element.getObjectName();
                final Version version = myConnection.getLastVersion(path, true);
                final String elementLastVersion = version == null ? null : version.getWholeName();
                if (elementLastVersion != null && myConnection.fileExistsInParent(element)) {
                    loadFile(path + CCParseUtil.CC_VERSION_SEPARATOR + elementLastVersion, builder, getRelativePath(path));
                }
            }

            public void processChangedDirectory(final HistoryElement element) throws IOException, VcsException {
            CCParseUtil.processChangedDirectory(element, myConnection, new ChangedStructureProcessor() {
              public void fileAdded(DirectoryChildElement child) throws VcsException {
                loadFile(child.getFullPath(), builder, getRelativePath(child.getPath()));
              }

              public void fileDeleted(DirectoryChildElement child) throws IOException {
                builder.deleteFile(new File(getRelativePath(child.getPath())), false);
              }

              public void directoryDeleted(DirectoryChildElement child) throws IOException {
                builder.deleteDirectory(new File(getRelativePath(child.getPath())), false);
              }

              public void directoryAdded(DirectoryChildElement child) throws VcsException, IOException {
                builder.createDirectory(new File(getRelativePath(child.getPath())));
                myConnection.processAllVersions(child.getFullPath(), getRelativePath(child.getPath()),createFileProcessor(builder));
              }
            });
          }

          public void processDestroyedFileVersion(final HistoryElement element) throws VcsException {
            processChangedFile(element);
          }
        });
      }
      else {
        myConnection.processAllVersions(lastVersion, createFileProcessor(builder), false, myUseCCCache);
      }
    } finally {
      if (myTempFile != null) {
        FileUtil.delete(myTempFile);
      }
    }
  }

  private VersionProcessor createFileProcessor(final PatchBuilder builder) {
    return new VersionProcessor() {
      public void processFile(final String fileFullPath,
                              final String relPath,
                              final String pname,
                              final String version,
                              final ClearCaseConnection clearCaseConnection,
                              final boolean text,
                              final boolean executable)
        throws VcsException {
        loadFile(fileFullPath, builder, relPath);
      }

      public void processDirectory(final String fileFullPath,
                                   final String relPath,
                                   final String pname,
                                   final String version, final ClearCaseConnection clearCaseConnection)
        throws VcsException {
        try {
          builder.createDirectory(new File(relPath));
        } catch (IOException e) {
          throw new VcsException(e);
        }            
      }

      public void finishProcessingDirectory() {
        
      }
    };
  }

  private String getRelativePath(final String path) {
    return myConnection.getRelativePath(path);
  }

  private void loadFile(final String line, final PatchBuilder builder, String relativePath) throws VcsException {
    try {
      final File tempFile = getTempFile();
      FileUtil.delete(tempFile);

      myConnection.loadFileContent(tempFile, line);
      if (tempFile.isFile()) {
        final String pathWithoutVersion =
          CCPathElement.replaceLastVersionAndReturnFullPathWithVersions(line, myConnection.getViewWholePath(), null);
        ClearCaseFileAttr fileAttr = myConnection.loadFileAttr(pathWithoutVersion + CCParseUtil.CC_VERSION_SEPARATOR);

        final String fileMode = fileAttr.isIsExecutable() ? EXECUTABLE_ATTR : null;
        if (fileAttr.isIsText()) {
          final FileInputStream input = new FileInputStream(tempFile);
          try {
            builder.changeOrCreateTextFile(new File(relativePath), fileMode, input, tempFile.length(), null);
          } finally {
            input.close();
          }
        }
        else {
          final FileInputStream input = new FileInputStream(tempFile);
          try {
            builder.changeOrCreateBinaryFile(new File(relativePath), fileMode, input, tempFile.length());
          } finally {
            input.close();
          }
        }

      }
    } catch (ExecutionException e) {
      throw new VcsException(e);
    } catch (InterruptedException e) {
      throw new VcsException(e);
    } catch (IOException primary) {
      //TW-10811 hotfix: threat files that cannot get own context as "rmelem'ed"
      if (primary.getMessage().contains("Operation \"get cleartext\" failed: not a ClearCase object.")) {
        try {
          LOG.warn(
            String.format("Could not get content of \"%s\", perhaps element was \"rmelem\"'ed. 'll produce deletion. Original message: %s",
                          line,
                          primary.getMessage()));
          builder.deleteFile(new File(relativePath), false);
        } catch (IOException secondary) {
          throw new VcsException(secondary.initCause(primary));//keep source exception as cause
        }
        return;
      }
      throw new VcsException(primary);
    }
  }

  public void dispose() {
    if (myTempFile != null) {
      FileUtil.delete(myTempFile);
      myTempFile = null;
    }
  }

  private synchronized File getTempFile() throws IOException {
    if (myTempFile == null) {
      myTempFile = FileUtil.createTempFile("cc", "temp");
    }
    return myTempFile;
  }
}
