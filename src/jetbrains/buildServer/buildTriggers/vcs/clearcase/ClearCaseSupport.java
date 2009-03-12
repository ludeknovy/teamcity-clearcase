/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import jetbrains.buildServer.Used;
import jetbrains.buildServer.buildTriggers.vcs.AbstractVcsPropertiesProcessor;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.structure.ClearCaseStructureCache;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.util.MultiMap;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.vcs.*;
import jetbrains.buildServer.vcs.patches.PatchBuilder;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ClearCaseSupport extends AbstractVcsSupport implements VcsPersonalSupport,
                                                                    LabelingSupport, VcsFileContentProvider,
                                                                    CollectChangesByIncludeRules, BuildPatchByIncludeRules
{
  @NonNls public static final String VIEW_PATH = "view-path";
  @NonNls public static final String TYPE = "TYPE";
  @NonNls private static final String UCM = "UCM";
  @NonNls private static final String GLOBAL_LABELS_VOB = "global-labels-vob";
  @NonNls private static final String USE_GLOBAL_LABEL = "use-global-label";

  private static final boolean USE_CC_CACHE = !"true".equals(System.getProperty("clearcase.disable.caches"));
  private static final String VOBS = "vobs/";
  private final @Nullable ClearCaseStructureCache myCache;

  public ClearCaseSupport(File baseDir) {
    if (baseDir != null) {
      myCache = new ClearCaseStructureCache(baseDir, this);
    }
    else {
      myCache = null;
    }
  }

  public ClearCaseSupport(SBuildServer server, ServerPaths serverPaths, EventDispatcher<BuildServerListener> dispatcher) {
    File cachesRootDir = new File(new File(serverPaths.getCachesDir()), "clearCase");
    if (!cachesRootDir.exists() && !cachesRootDir.mkdirs()) {
      myCache = null;
      return;
    }
    myCache = new ClearCaseStructureCache(cachesRootDir, this);
    if (USE_CC_CACHE) {
      myCache.register(server, dispatcher);
    }
  }

  public ClearCaseConnection createConnection(final VcsRoot root, final FileRule includeRule) throws VcsException {
    return doCreateConnection(root, includeRule, false);
  }

  public ClearCaseConnection createConnection(final VcsRoot root, final FileRule includeRule, final boolean checkCSChange) throws VcsException {
    return doCreateConnection(root, includeRule, checkCSChange);
  }

  private ClearCaseConnection doCreateConnection(final VcsRoot root, final FileRule includeRule, final boolean checkCSChange) throws VcsException {
    String viewPath = root.getProperty(VIEW_PATH);
    boolean isUCM = root.getProperty(TYPE, UCM).equals(UCM);
    if (viewPath.endsWith("/") || viewPath.endsWith("\\")) {
      viewPath = viewPath.substring(0, viewPath.length() - 1);
    }
    if (includeRule.getFrom().length() > 0) {
      viewPath = viewPath + File.separator + includeRule.getFrom();
    }
    try {
      return new ClearCaseConnection(viewPath, isUCM, myCache, root, checkCSChange);
    } catch (Exception e) {
      throw new VcsException(e);
    }
  }

  private ChangedFilesProcessor createCollectingChangesFileProcessor(final MultiMap<CCModificationKey, VcsChange> key2changes,
                                                                     final ClearCaseConnection connection) {
    return new ChangedFilesProcessor() {


      public void processChangedDirectory(final HistoryElement element) throws IOException, VcsException {
        CCParseUtil.processChangedDirectory(element, connection, createChangedStructureProcessor(element, key2changes, connection));
      }

      public void processDestroyedFileVersion(final HistoryElement element) throws VcsException {        
      }

      public void processChangedFile(final HistoryElement element) throws VcsException {
        if (element.getObjectVersionInt() > 1) {
          //TODO lesya full path

          String pathWithoutVersion = connection.getParentRelativePathWithVersions(element.getObjectName(), true);

          final String versionAfterChange = pathWithoutVersion + CCParseUtil.CC_VERSION_SEPARATOR + element.getObjectVersion();
          final String versionBeforeChange = pathWithoutVersion + CCParseUtil.CC_VERSION_SEPARATOR + element.getPreviousVersion();


          addChange(element, element.getObjectName(), connection, VcsChangeInfo.Type.CHANGED, versionBeforeChange, versionAfterChange,
                    key2changes);
        }
      }

    };
  }

  private ChangedStructureProcessor createChangedStructureProcessor(final HistoryElement element,
                                                                    final MultiMap<CCModificationKey, VcsChange> key2changes,
                                                                    final ClearCaseConnection connection) {
    return new ChangedStructureProcessor() {
      public void fileAdded(DirectoryChildElement child) throws VcsException, IOException {
        if (connection.versionIsInsideView(child.getPathWithoutVersion(), child.getStringVersion(), true)) {
          addChange(element, child.getFullPath(), connection, VcsChangeInfo.Type.ADDED, null, getVersion(child, connection), key2changes);
        }
      }

      public void fileDeleted(DirectoryChildElement child) throws VcsException, IOException {
        if (connection.versionIsInsideView(child.getPathWithoutVersion(), child.getStringVersion(), true)) {
          addChange(element, child.getFullPath(), connection, VcsChangeInfo.Type.REMOVED, getVersion(child, connection), null, key2changes);
        }
      }

      public void directoryDeleted(DirectoryChildElement child) throws VcsException, IOException {
        if (connection.versionIsInsideView(child.getPathWithoutVersion(), child.getStringVersion(), false)) {
          addChange(element, child.getFullPath(), connection, VcsChangeInfo.Type.DIRECTORY_REMOVED, getVersion(child, connection), null, key2changes);
        }
      }

      public void directoryAdded(DirectoryChildElement child) throws VcsException, IOException {
        if (connection.versionIsInsideView(child.getPathWithoutVersion(), child.getStringVersion(), false)) {
          addChange(element, child.getFullPath(), connection, VcsChangeInfo.Type.DIRECTORY_ADDED, null, getVersion(child, connection), key2changes);
        }
      }
    };
  }

  private String getVersion(final DirectoryChildElement child, final ClearCaseConnection connection) throws VcsException {
    return connection.getObjectRelativePathWithVersions(child.getFullPath(), DirectoryChildElement.Type.FILE.equals(child.getType()));
  }

  private void addChange(final HistoryElement element,
                         final String childFullPath,
                         final ClearCaseConnection connection,
                         final VcsChangeInfo.Type type,
                         final String beforeVersion,
                         final String afterVersion,
                         final MultiMap<CCModificationKey, VcsChange> key2changes) throws VcsException {
    final CCModificationKey modificationKey = new CCModificationKey(element.getDate(), element.getUser());
    key2changes.putValue(modificationKey, createChange(type, connection, beforeVersion, afterVersion, childFullPath));
    CCModificationKey realKey = findKey(modificationKey, key2changes);
    if (realKey != null) {
      realKey.getCommentHolder().update(element.getActivity(), element.getComment(), connection.getVersionDescription(childFullPath));
    }
  }

  @Nullable
  private CCModificationKey findKey(final CCModificationKey modificationKey, final MultiMap<CCModificationKey, VcsChange> key2changes) {
    for (CCModificationKey key : key2changes.keySet()) {
      if (key.equals(modificationKey)) return key;
    }
    return null;
  }

  private VcsChange createChange(final VcsChangeInfo.Type type,
                                 ClearCaseConnection connection,
                                 final String beforeVersion,
                                 final String afterVersion,
                                 final String childFullPath) throws VcsException {
    String relativePath = connection.getObjectRelativePathWithoutVersions(childFullPath, isFile(type));
    return new VcsChange(type, relativePath, relativePath, beforeVersion, afterVersion);
  }

  private boolean isFile(final VcsChangeInfo.Type type) {
    switch (type) {
      case ADDED:
      case CHANGED:
      case REMOVED: {
        return true;
      }

      default: {
        return false;
      }
    }
  }

  public void buildPatch(VcsRoot root, String fromVersion, String toVersion, PatchBuilder builder, final IncludeRule includeRule) throws IOException, VcsException {
    final ClearCaseConnection connection = createConnection(root, includeRule, true);
    try {
      new CCPatchProvider(connection, USE_CC_CACHE).buildPatch(builder, fromVersion, toVersion);
    } catch (ExecutionException e) {
      throw new VcsException(e);
    } catch (ParseException e) {
      throw new VcsException(e);
    } finally {
      connection.dispose();
    }
  }

  @NotNull
  public byte[] getContent(@NotNull final VcsModification vcsModification,
                           @NotNull final VcsChangeInfo change,
                           @NotNull final VcsChangeInfo.ContentType contentType,
                           @NotNull final VcsRoot vcsRoot) throws VcsException {

    final ClearCaseConnection connection = createConnection(vcsRoot, IncludeRule.createDefaultInstance());
    final String filePath = new File(connection.getViewName()).getParent() + File.separator + (
      contentType == VcsChangeInfo.ContentType.BEFORE_CHANGE
      ? change.getBeforeChangeRevisionNumber()
      : change.getAfterChangeRevisionNumber());

    return getFileContent(connection, filePath);

  }

  private byte[] getFileContent(final ClearCaseConnection connection, final String filePath) throws VcsException {
    try {
      final File tempFile = FileUtil.createTempFile("cc", "tmp");
      FileUtil.delete(tempFile);

      try {

        connection.loadFileContent(tempFile, filePath);
        if (tempFile.isFile()) {
          return FileUtil.loadFileBytes(tempFile);
        } else {
          throw new VcsException("Cannot get content of " + filePath);
        }
      } finally {
        FileUtil.delete(tempFile);
      }
    } catch (ExecutionException e) {
      throw new VcsException(e);
    } catch (InterruptedException e) {
      throw new VcsException(e);
    } catch (IOException e) {
      throw new VcsException(e);
    }
  }

  @NotNull
  public byte[] getContent(@NotNull final String filePath, @NotNull final VcsRoot versionedRoot, @NotNull final String version) throws VcsException {
    String preparedPath = filePath.replace('/', File.separatorChar);
    preparedPath = preparedPath.replace('\\', File.separatorChar);
    final ClearCaseConnection connection = createConnection(versionedRoot, IncludeRule.createDefaultInstance());
    try {
      connection.collectChangesToIgnore(version);
      String path = new File(connection.getViewName()).getParent() + File.separator +
                    connection.getObjectRelativePathWithVersions(connection.getViewName() + File.separator + preparedPath, true);
      return getFileContent(connection, path);
    } finally {
      try {
        connection.dispose();
      } catch (IOException e) {
        //ignore
      }
    }
  }

  @NotNull
  public String getName() {
    return "clearcase";
  }

  @NotNull
  @Used("jsp")
  public String getDisplayName() {
    return "ClearCase";
  }

  @NotNull
  public PropertiesProcessor getVcsPropertiesProcessor() {
    return new AbstractVcsPropertiesProcessor() {
      public Collection<InvalidProperty> process(Map<String, String> properties) {

        final List<InvalidProperty> result = new ArrayList<InvalidProperty>();
        if (isEmpty(properties.get(ClearCaseSupport.VIEW_PATH))) {
          result.add(new InvalidProperty(ClearCaseSupport.VIEW_PATH, "View path must be specified"));
        } else {
          int countBefore = result.size();
          checkDirectoryProperty(ClearCaseSupport.VIEW_PATH, properties.get(ClearCaseSupport.VIEW_PATH), result);
          if (result.size() == countBefore) {
            checkViewPathProperty(ClearCaseSupport.VIEW_PATH, properties.get(ClearCaseSupport.VIEW_PATH), result);
          }
          checkGlobalLabelsVOBProperty(properties, result);
        }

        return result;
      }

      private void checkGlobalLabelsVOBProperty(final Map<String, String> properties, final List<InvalidProperty> result) {
        final boolean useGlobalLabel = "true".equals(properties.get(USE_GLOBAL_LABEL));
        if (!useGlobalLabel) return;
        final String globalLabelsVOB = properties.get(GLOBAL_LABELS_VOB);
        if (globalLabelsVOB == null || "".equals(globalLabelsVOB.trim())) {
          result.add(new InvalidProperty(GLOBAL_LABELS_VOB, "Global labels VOB must be specified"));
        }
        // todo test if specified VOB exists
      }

      private void checkViewPathProperty(final String propertyName, final String propertyValue, final List<InvalidProperty> result) {
        final File viewPath = new File(propertyValue);
        File viewRoot = null;
        try {
          viewRoot = ClearCaseConnection.ourProcessExecutor.getViewRoot(propertyValue);
        } catch (VcsException e) {
          result.add(new InvalidProperty(propertyName, e.getLocalizedMessage()));
        }
        if (viewPath.getParentFile().equals(viewRoot)) {
          result.add(new InvalidProperty(propertyName, "Please select some project directory inside the VOB one. In case your project is in the specified directory you can select any subdirectory and add checkout rule \"+:..=>\" to this vcs root."));
        }
      }
    };
  }

  @NotNull
  public String getVcsSettingsJspFilePath() {
    return "clearcaseSettings.jsp";
  }

  @NotNull
  public String getCurrentVersion(@NotNull final VcsRoot root) throws VcsException {
    return CCParseUtil.formatDate(new Date());
  }

  public boolean isCurrentVersionExpensive() {
    return false;
  }

  public boolean ignoreServerCachesFor(@NotNull final VcsRoot root) {
    return false;
  }

  @NotNull
  public String getVersionDisplayName(@NotNull final String version, @NotNull final VcsRoot root) throws VcsException {
    return version;
  }

  @NotNull
  public Comparator<String> getVersionComparator() {
    return new VcsSupportUtil.DateVersionComparator(CCParseUtil.getDateFormat());
  }

  public boolean isAgentSideCheckoutAvailable() {
    return false;  //To change body of implemented methods use File | Settings | File Templates.
  }

  @NotNull
  public String describeVcsRoot(VcsRoot vcsRoot) {
    return "clearcase: " + vcsRoot.getProperty(VIEW_PATH);
  }

  public boolean isTestConnectionSupported() {
    return true;
  }

  public String testConnection(@NotNull VcsRoot vcsRoot) throws VcsException {
    final ClearCaseConnection caseConnection = createConnection(vcsRoot, IncludeRule.createDefaultInstance());
    try {
      try {
        return caseConnection.testConnection();
      } finally {
        caseConnection.dispose();
      }
    } catch (IOException e) {
      throw new VcsException(e);
    }
  }

  @Nullable
  public Map<String, String> getDefaultVcsProperties() {
    return new HashMap<String, String>();
  }

  @NotNull
  public Collection<String> mapFullPath(@NotNull final VcsRootEntry rootEntry, @NotNull final String fullPath) {

    String normFullPath = fullPath.replace("\\", "/");

    String viewPath = rootEntry.getVcsRoot().getProperty(VIEW_PATH);

    if (viewPath == null) {
      Loggers.VCS.debug("CC.MapFullPath: View path not defined");
      return Collections.emptySet();
    }

    File file = new File(viewPath);
    File view = CCParseUtil.findViewPath(file);

    if (view == null) {
      Loggers.VCS.debug("CC.MapFullPath: View base dir not found for " + file.getAbsolutePath());
      return Collections.emptySet();
    }

    Loggers.VCS.debug("CC.MapFullPath: View base for " + viewPath + " is " + view.getAbsolutePath());

    String serverViewRelativePath = FileUtil.getRelativePath(view, file);

    Loggers.VCS.debug("CC.MapFullPath: Relative path on server is " + serverViewRelativePath);

    if (serverViewRelativePath == null) return Collections.emptySet();

    serverViewRelativePath = serverViewRelativePath.replace("\\", "/");

    serverViewRelativePath = cutOffVobsDir(serverViewRelativePath);

    normFullPath = cutOffVobsDir(normFullPath);


    if (isAncestor(serverViewRelativePath, normFullPath)) {
      String result = normFullPath.substring(serverViewRelativePath.length());
      if (result.startsWith("/") || result.startsWith("\\")) {
        result = result.substring(1);
      }
      Loggers.VCS.debug("CC.MapFullPath: File " + normFullPath + " is under " + serverViewRelativePath + " result is " + result);
      return Collections.singleton(result);

    }
    else {
      Loggers.VCS.debug("CC.MapFullPath: File " + normFullPath + " is not under " + serverViewRelativePath);
      return Collections.emptySet();
    }
  }

  private String cutOffVobsDir(String serverViewRelativePath) {
    if (StringUtil.startsWithIgnoreCase(serverViewRelativePath, VOBS)) {
      serverViewRelativePath = serverViewRelativePath.substring(VOBS.length());
    }
    return serverViewRelativePath;
  }

  private boolean isAncestor(String sRelPath, final String relPath) {
    return sRelPath.equalsIgnoreCase(relPath) || StringUtil.startsWithIgnoreCase(relPath, sRelPath + "/");
  }

  @NotNull
  public VcsSupportCore getCore() {
    return this;
  }

  public VcsPersonalSupport getPersonalSupport() {
    return this;
  }

  public LabelingSupport getLabelingSupport() {
    return this;
  }

  @NotNull
  public VcsFileContentProvider getContentProvider() {
    return this;
  }

  @NotNull
  public CollectChangesPolicy getCollectChangesPolicy() {
    return this;
  }

  @NotNull
  public BuildPatchPolicy getBuildPatchPolicy() {
    return this;
  }

  public List<ModificationData> collectChanges(final VcsRoot root,
                                                    final String fromVersion,
                                                    final String currentVersion,
                                                    final IncludeRule includeRule) throws VcsException {
    final ClearCaseConnection connection = createConnection(root, includeRule);

    try {
      try {
        connection.collectChangesToIgnore(currentVersion);
      } catch (Exception e) {
        throw new VcsException(e);
      }

      final ArrayList<ModificationData> list = new ArrayList<ModificationData>();
      final MultiMap<CCModificationKey, VcsChange> key2changes = new MultiMap<CCModificationKey, VcsChange>();

      final ChangedFilesProcessor fileProcessor = createCollectingChangesFileProcessor(key2changes, connection);


      try {

        CCParseUtil.processChangedFiles(connection, fromVersion, currentVersion, fileProcessor);

        for (CCModificationKey key : key2changes.keySet()) {
          final List<VcsChange> changes = key2changes.get(key);
          final Date date = new SimpleDateFormat(CCParseUtil.OUTPUT_DATE_FORMAT).parse(key.getDate());
          final String version = CCParseUtil.formatDate(new Date(date.getTime() + 1000));
          list.add(new ModificationData(date, changes, key.getCommentHolder().toString(), key.getUser(), root, version, version));
        }

      } catch (Exception e) {
        throw new VcsException(e);
      }

      Collections.sort(list, new Comparator<ModificationData>() {
        public int compare(final ModificationData o1, final ModificationData o2) {
          return o1.getVcsDate().compareTo(o2.getVcsDate());
        }
      });

      return list;
    } finally {
      try {
        connection.dispose();
      } catch (IOException e) {
        //ignore
      }
    }

  }

  public String label(@NotNull final String label, @NotNull final String version, @NotNull final VcsRoot root, @NotNull final CheckoutRules checkoutRules) throws VcsException {
    createLabel(label, root);
    for (IncludeRule includeRule : checkoutRules.getRootIncludeRules()) {
      final ClearCaseConnection connection = createConnection(root, includeRule);
      try {
        connection.processAllVersions(version, new VersionProcessor() {
          public void processFile(final String fileFullPath,
                                  final String relPath,
                                  final String pname,
                                  final String version,
                                  final ClearCaseConnection clearCaseConnection,
                                  final boolean text,
                                  final boolean executable)
          throws VcsException {
            try {
              clearCaseConnection.mklabel(version, fileFullPath, label);
            } catch (IOException e) {
              throw new VcsException(e);
            }
          }

          public void processDirectory(final String fileFullPath,
                                       final String relPath,
                                       final String pname,
                                       final String version, final ClearCaseConnection clearCaseConnection)
          throws VcsException {
            try {
              clearCaseConnection.mklabel(version, fileFullPath, label);
            } catch (IOException e) {
              throw new VcsException(e);
            }
          }

          public void finishProcessingDirectory() {

          }
        }, true, true);
      } finally {
        try {
          connection.dispose();
        } catch (IOException e) {
          //ignore
        }
      }
    }
    return label;
  }

  private void createLabel(final String label, final VcsRoot root) throws VcsException {
    final boolean useGlobalLabel = "true".equals(root.getProperty(USE_GLOBAL_LABEL));

    String[] command;
    if (useGlobalLabel) {
      final String globalLabelsVob = root.getProperty(GLOBAL_LABELS_VOB);
      command = new String[]{"mklbtype", "-global", "-c", "Label created by TeamCity", label + "@" + globalLabelsVob};
    } else {
      command = new String[]{"mklbtype", "-c", "Label created by TeamCity", label};
    }

      try {
        InputStream input = ClearCaseConnection.executeSimpleProcess(root.getProperty(VIEW_PATH), command);
      try {
        input.close();
      } catch (IOException e) {
        //ignore
      }
    } catch (VcsException e) {
      if (!e.getLocalizedMessage().contains("already exists")) {
        throw e;
      }
    }
  }

  public boolean sourcesUpdatePossibleIfChangesNotFound(@NotNull final VcsRoot root) {
    return false;
  }

  @NotNull
  public IncludeRuleChangeCollector obtainChangeCollector(@NotNull final VcsRoot root,
                                                          @NotNull final String fromVersion,
                                                          @Nullable final String currentVersion) throws VcsException {
    return new IncludeRuleChangeCollector() {
      @NotNull
      public List<ModificationData> collectChanges(@NotNull final IncludeRule includeRule) throws VcsException {
        return ClearCaseSupport.this.collectChanges(root, fromVersion, currentVersion, includeRule);
      }

      public void dispose() {
        //nothing to do
      }
    };
  }

  @NotNull
  public IncludeRulePatchBuilder obtainPatchBuilder(@NotNull final VcsRoot root,
                                                    @Nullable final String fromVersion,
                                                    @NotNull final String toVersion) {
    return new IncludeRulePatchBuilder() {
      public void buildPatch(@NotNull final PatchBuilder builder, @NotNull final IncludeRule includeRule) throws IOException, VcsException {
        ClearCaseSupport.this.buildPatch(root, fromVersion, toVersion, builder, includeRule);
      }

      public void dispose() {
        //nothing to do
      }
    };
  }
}
