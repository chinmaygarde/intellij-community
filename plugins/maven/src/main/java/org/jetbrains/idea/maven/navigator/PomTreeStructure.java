package org.jetbrains.idea.maven.navigator;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiManager;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.treeStructure.SimpleNode;
import com.intellij.ui.treeStructure.SimpleTree;
import com.intellij.ui.treeStructure.SimpleTreeStructure;
import org.apache.maven.artifact.Artifact;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.core.util.IdeaAPIHelper;
import org.jetbrains.idea.maven.core.util.MavenId;
import org.jetbrains.idea.maven.embedder.EmbedderFactory;
import org.jetbrains.idea.maven.events.MavenEventsHandler;
import org.jetbrains.idea.maven.project.MavenProjectModel;
import org.jetbrains.idea.maven.repository.MavenPluginInfo;
import org.jetbrains.idea.maven.repository.MavenPluginsRepository;
import org.jetbrains.idea.maven.state.MavenProjectsManager;

import javax.swing.*;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.InputEvent;
import java.util.*;
import java.util.List;

public abstract class PomTreeStructure extends SimpleTreeStructure {
  private static final Icon iconProjectRoot = IconLoader.getIcon("/general/ijLogo.png");
  private static final Icon iconPom = IconLoader.getIcon("/images/mavenProject.png");

  private static final Icon iconProfilesOpen = IconLoader.getIcon("/images/profilesOpen.png");
  private static final Icon iconProfilesClosed = IconLoader.getIcon("/images/profilesClosed.png");

  private static final Icon iconPhase = IconLoader.getIcon("/images/phase.png");
  private static final Icon iconPhasesOpen = IconLoader.getIcon("/images/phasesOpen.png");
  private static final Icon iconPhasesClosed = IconLoader.getIcon("/images/phasesClosed.png");

  private static final Icon iconDependency = IconLoader.getIcon("/images/phase.png");
  private static final Icon iconDependenciesOpen = IconLoader.getIcon("/images/phasesOpen.png");
  private static final Icon iconDependenciesClosed = IconLoader.getIcon("/images/phasesClosed.png");

  private static final Icon iconPluginsOpen = IconLoader.getIcon("/images/phasesOpen.png");
  private static final Icon iconPluginsClosed = IconLoader.getIcon("/images/phasesClosed.png");
  private static final Icon iconPlugin = IconLoader.getIcon("/images/mavenPlugin.png");
  private static final Icon iconPluginGoal = IconLoader.getIcon("/images/pluginGoal.png");

  private static final Icon iconExtensionsOpen = IconLoader.getIcon("/images/phasesOpen.png");
  private static final Icon iconExtensionsClosed = IconLoader.getIcon("/images/phasesClosed.png");
  private static final Icon iconExtension = IconLoader.getIcon("/images/mavenPlugin.png");

  private static final Icon iconModulesOpen = IconLoader.getIcon("/images/nestedPomsOpen.png");
  private static final Icon iconModulesClosed = IconLoader.getIcon("/images/nestedPomsClosed.png");

  final ProjectFileIndex myFileIndex;
  final Project project;

  protected MavenProjectsManager myProjectsManager;

  private final MavenPluginsRepository myRepository;
  protected final MavenEventsHandler myEventsHandler;

  protected final RootNode root = new RootNode();

  private final Collection<String> standardPhases = EmbedderFactory.getStandardPhasesList();
  final Collection<String> standardGoals = EmbedderFactory.getStandardGoalsList();

  // TODO : update tree after local repository location change
  public PomTreeStructure(Project project,
                          MavenProjectsManager cache,
                          MavenPluginsRepository repository,
                          final MavenEventsHandler eventsHandler) {
    this.project = project;
    myProjectsManager = cache;
    myRepository = repository;
    myEventsHandler = eventsHandler;
    myFileIndex = ProjectRootManager.getInstance(project).getFileIndex();
  }

  public MavenPluginsRepository getRepository() {
    return myRepository;
  }

  public Object getRootElement() {
    return root;
  }

  protected abstract PomTreeViewSettings getTreeViewSettings();

  protected abstract void updateTreeFrom(@Nullable SimpleNode node);

  protected boolean isMinimalView() {
    return false;
  }

  private static final Comparator<SimpleNode> nodeComparator = new Comparator<SimpleNode>() {
    public int compare(SimpleNode o1, SimpleNode o2) {
      return getRepr(o1).compareToIgnoreCase(getRepr(o2));
    }

    private String getRepr(SimpleNode node) {
      return ((node instanceof ModuleNode) && ((ModuleNode)node).getModule() == null) ? "" : node.getName();
    }
  };

  private static <T extends SimpleNode> void insertSorted(List<T> list, T newObject) {
    int pos = Collections.binarySearch(list, newObject, nodeComparator);
    list.add(pos >= 0 ? pos : -pos - 1, newObject);
  }

  public static List<SimpleNode> getSelectedNodes(SimpleTree tree) {
    List<SimpleNode> nodes = new ArrayList<SimpleNode>();
    TreePath[] treePaths = tree.getSelectionPaths();
    if (treePaths != null) {
      for (TreePath treePath : treePaths) {
        nodes.add(tree.getNodeFor(treePath));
      }
    }
    return nodes;
  }

  public static <T extends SimpleNode> List<T> getSelectedNodes(SimpleTree tree, Class<T> aClass) {
    final List<T> filtered = new ArrayList<T>();
    for (SimpleNode node : getSelectedNodes(tree)) {
      if ((aClass != null) && (!aClass.isInstance(node))) {
        filtered.clear();
        break;
      }
      //noinspection unchecked
      filtered.add((T)node);
    }
    return filtered;
  }

  @Nullable
  public static PomNode getCommonParent(Collection<? extends CustomNode> goalNodes) {
    PomNode parent = null;
    for (CustomNode node : goalNodes) {
      PomNode nextParent = node.getParent(PomNode.class);
      if (parent == null) {
        parent = nextParent;
      }
      else if (parent != nextParent) {
        return null;
      }
    }
    return parent;
  }

  class CustomNode extends SimpleNode {

    private CustomNode structuralParent;

    public CustomNode(CustomNode parent) {
      super(project);
      setStructuralParent(parent);
    }

    public void setStructuralParent(CustomNode structuralParent) {
      this.structuralParent = structuralParent;
    }

    public SimpleNode[] getChildren() {
      return SimpleNode.NO_CHILDREN;
    }

    public <T extends CustomNode> T getParent(Class<T> aClass) {
      CustomNode node = this;
      while (true) {
        node = node.structuralParent;
        if (node == null || aClass.isInstance(node)) {
          //noinspection unchecked,ConstantConditions
          return (T)node;
        }
      }
    }

    boolean isVisible() {
      return true;
    }

    void display(DisplayList list) {
      if (isVisible()) {
        list.insert(this);
      }
    }

    @Nullable
    @NonNls
    String getActionId() {
      return null;
    }

    @Nullable
    @NonNls
    String getMenuId() {
      return null;
    }

    void updateSubTree() {
      updateTreeFrom(this);
    }

    public void handleDoubleClickOrEnter(final SimpleTree tree, final InputEvent inputEvent) {
      final String actionId = getActionId();
      if (actionId != null) {
        IdeaAPIHelper.executeAction(actionId, inputEvent);
      }
    }
  }

  interface DisplayList {
    void add(Iterable<? extends CustomNode> nodes);

    void add(CustomNode node);

    void insert(CustomNode node);

    void sort();
  }

  abstract class ListNode extends CustomNode {

    final List<SimpleNode> displayList = new ArrayList<SimpleNode>();

    final DisplayList myDisplayList = new DisplayList() {
      public void insert(CustomNode node) {
        displayList.add(node);
      }

      public void sort() {
        Collections.sort(displayList, nodeComparator);
      }


      public void add(Iterable<? extends CustomNode> nodes) {
        for (CustomNode node : nodes) {
          add(node);
        }
      }

      public void add(CustomNode node) {
        node.display(this);
      }
    };

    public ListNode(CustomNode parent) {
      super(parent);
    }

    public SimpleNode[] getChildren() {
      displayList.clear();
      displayChildren(myDisplayList);
      return displayList.toArray(new SimpleNode[displayList.size()]);
    }

    protected abstract void displayChildren(DisplayList displayList);
  }

  public class RootNode extends PomGroupNode {

    public final List<ModuleNode> moduleNodes = new ArrayList<ModuleNode>();

    public RootNode() {
      super(null);
      addPlainText(NavigatorBundle.message("node.root"));
    }

    protected void displayChildren(DisplayList displayList) {
      displayList.add(moduleNodes);
      displayList.add(pomNodes);
      displayList.sort();
    }

    @NotNull
    protected CustomNode getVisibleParent() {
      return root;
    }

    void rebuild(final Collection<PomNode> allPomNodes) {
      moduleNodes.clear();
      pomNodes.clear();
      for (PomNode node : allPomNodes) {
        node.unlinkNested();
      }
      for (PomNode pomNode : allPomNodes) {
        addToStructure(pomNode);
      }
    }

    void addToStructure(PomNode pomNode) {
      if (!getTreeViewSettings().showIgnored && myProjectsManager.isIgnored(pomNode.project)) {
        return;
      }
      if (getTreeViewSettings().groupByModule) {
        findModuleNode(myFileIndex.getModuleForFile(pomNode.getProjectNode().getFile())).addUnder(pomNode);
      }
      else {
        addUnder(pomNode);
      }
    }

    private ModuleNode findModuleNode(Module module) {
      for (ModuleNode moduleNode : moduleNodes) {
        if (moduleNode.getModule() == module) {
          return moduleNode;
        }
      }
      ModuleNode newNode = new ModuleNode(this, module);
      insertSorted(moduleNodes, newNode);
      updateSubTree();
      return newNode;
    }
  }

  public abstract class PomGroupNode extends ListNode {

    public final List<PomNode> pomNodes = new ArrayList<PomNode>();

    public PomGroupNode(CustomNode parent) {
      super(parent);
    }

    boolean isVisible() {
      return !pomNodes.isEmpty();
    }

    protected void displayChildren(DisplayList displayList) {
      displayList.add(pomNodes);
    }

    protected boolean addUnderExisting(final PomNode newNode) {
      for (PomNode node : pomNodes) {
        if (node.isAncestor(newNode)) {
          node.addNestedPom(newNode);
          return true;
        }
      }
      return false;
    }

    public boolean addUnder(PomNode newNode) {
      if (addUnderExisting(newNode)) {
        return true;
      }

      for (PomNode child : removeChildren(newNode, new ArrayList<PomNode>())) {
        newNode.addNestedPom(child);
      }

      add(newNode);
      return true;
    }

    protected Collection<PomNode> removeChildren(final PomNode parent, final Collection<PomNode> children) {
      return removeOwnChildren(parent, children);
    }

    protected final Collection<PomNode> removeOwnChildren(final PomNode parent, final Collection<PomNode> children) {
      for (PomNode node : pomNodes) {
        if (parent.isAncestor(node)) {
          children.add(node);
        }
      }
      pomNodes.removeAll(children);
      return children;
    }

    protected void add(PomNode pomNode) {
      boolean wasVisible = isVisible();

      pomNode.setStructuralParent(this);
      insertSorted(pomNodes, pomNode);

      updateGroupNode(wasVisible);
    }

    public void remove(PomNode pomNode) {
      boolean wasVisible = isVisible();

      pomNode.setStructuralParent(null);
      pomNodes.remove(pomNode);
      adoptOrphans(pomNode);

      updateGroupNode(wasVisible);
    }

    private void adoptOrphans(final PomNode pomNode) {
      PomGroupNode newParent = getNewParentForOrphans();
      newParent.merge(pomNode.modulePomsNode);
      newParent.merge(pomNode.nonModulePomsNode);
      if (newParent != this) {
        updateTreeFrom(newParent);
      }
    }

    protected PomGroupNode getNewParentForOrphans() {
      return this;
    }

    public void reinsert(PomNode pomNode) {
      pomNodes.remove(pomNode);
      insertSorted(pomNodes, pomNode);
    }

    protected void merge(PomGroupNode groupNode) {
      for (PomNode pomNode : groupNode.pomNodes) {
        insertSorted(pomNodes, pomNode);
      }
      groupNode.clear();
    }

    public void clear() {
      pomNodes.clear();
    }

    private void updateGroupNode(boolean wasVisible) {
      if (wasVisible && isVisible()) {
        updateSubTree();
      }
      else {
        updateTreeFrom(getVisibleParent());
      }
    }

    @NotNull
    protected abstract CustomNode getVisibleParent();
  }

  public class ModuleNode extends PomGroupNode {
    private final Module module;

    public ModuleNode(RootNode parent, Module module) {
      super(parent);
      this.module = module;
      if (module != null) {
        addPlainText(module.getName());
        ModuleType moduleType = module.getModuleType();
        setIcons(moduleType.getNodeIcon(false), moduleType.getNodeIcon(true));
      }
      else {
        addPlainText(NavigatorBundle.message("node.project.root"));
        addColoredFragment(" " + NavigatorBundle.message("node.project.root.descr"), SimpleTextAttributes.GRAY_ATTRIBUTES);
        setUniformIcon(iconProjectRoot);
      }
    }

    public Module getModule() {
      return module;
    }

    boolean isVisible() {
      return super.isVisible() && getTreeViewSettings().groupByModule;
    }

    protected void display(DisplayList displayList) {
      super.display(displayList);
      if (!isVisible()) {
        displayChildren(displayList);
        displayList.sort();
      }
    }

    @NotNull
    protected CustomNode getVisibleParent() {
      return root;
    }
  }

  public class PomNode extends ListNode {
    final private MavenProjectModel.Node project;
    private String savedPath = "";
    private String actionIdPrefix = "";

    private LifecycleNode lifecycleNode;
    private ProfilesNode profilesNode;
    private DependenciesNode dependenciesNode;
    private PluginsNode  pluginsNode;
    private ExtensionsNode extensionsNode;
    public NestedPomsNode modulePomsNode;
    public NestedPomsNode nonModulePomsNode;

    public PomNode(MavenProjectModel.Node project) {
      super(null);
      this.project = project;

      lifecycleNode = new LifecycleNode(this);
      profilesNode = new ProfilesNode(this);
      dependenciesNode = new DependenciesNode(this);
      pluginsNode = new PluginsNode(this);
      extensionsNode = new ExtensionsNode(this);
      modulePomsNode = new ModulePomsNode(this);
      nonModulePomsNode = new NonModulePomsNode(this);

      modulePomsNode.sibling = nonModulePomsNode;
      nonModulePomsNode.sibling = modulePomsNode;

      setUniformIcon(iconPom);

      updateNode();
    }

    public VirtualFile getFile() {
      return project.getFile();
    }

    protected void display(DisplayList displayList) {
      displayList.insert(this);
      if (!modulePomsNode.isVisible()) {
        modulePomsNode.displayChildren(displayList);
      }
      if (!nonModulePomsNode.isVisible()) {
        nonModulePomsNode.displayChildren(displayList);
      }
    }

    protected void displayChildren(DisplayList displayList) {
      displayList.add(profilesNode);
      displayList.add(lifecycleNode);
      displayList.add(dependenciesNode);
      displayList.add(pluginsNode);
      displayList.add(extensionsNode);
      displayList.add(modulePomsNode);
      displayList.add(nonModulePomsNode);
    }

    @Nullable
    @NonNls
    protected String getActionId() {
      return "Maven.Run";
    }

    @Nullable
    @NonNls
    protected String getMenuId() {
      return "Maven.PomMenu";
    }

    public String getId() {
      final String name = project.getProjectName();
      if (!StringUtil.isEmptyOrSpaces(name)) {
        return name;
      }

      final String artifactId = project.getMavenProject().getArtifactId();
      if (!StringUtil.isEmptyOrSpaces(artifactId)) {
        return artifactId;
      }

      return NavigatorBundle.message("node.pom.unnamed");
    }

    public MavenProjectModel.Node getProjectNode() {
      return project;
    }

    private VirtualFile getDirectory() {
      //noinspection ConstantConditions
      return project.getDirectoryFile();
    }

    public boolean isAncestor(PomNode that) {
      return VfsUtil.isAncestor(getDirectory(), that.getDirectory(), true);
    }

    @Nullable
    public Navigatable getNavigatable() {
      return PsiManager.getInstance(PomTreeStructure.this.project).findFile(project.getFile());
    }

    private void updateNode() {
      updateText();

      lifecycleNode.updateGoals();
      createProfileNodes();
      createDependenciesNode();
      createPluginsNode();
      createExtensionsNode();
      regroupNested();
    }

    private void createDependenciesNode() {
      dependenciesNode.clear();
      for (Artifact each : project.getDependencies()) {
        dependenciesNode.add(new DependencyNode(this, new MavenId(each)));
      }
    }

    private void createPluginsNode() {
      pluginsNode.clear();
      for (MavenId mavenId : project.getPluginsIds()) {
        if (!hasPlugin(mavenId)) {
          pluginsNode.add(new PluginNode(this, mavenId));
        }
      }
    }

    public boolean hasPlugin(final MavenId id) {
      for (PluginNode node : pluginsNode.pluginNodes) {
        if (node.getId().matches(id)) {
          return true;
        }
      }
      return false;
    }

    private void createExtensionsNode() {
      extensionsNode.clear();
      for (Artifact each : project.getExtensions()) {
        extensionsNode.add(new ExtensionNode(this, new MavenId(each)));
      }
    }

    private void updateText() {
      clearColoredText();
      if (myProjectsManager.isIgnored(project)) {
        addColoredFragment(getId(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, Color.GRAY));
      }
      else {
        addPlainText(getId());
      }
      savedPath = project.getFile().getPath();
      actionIdPrefix = myEventsHandler.getActionId(savedPath, null);
      addColoredFragment(" (" + getDirectory().getPath() + ")", SimpleTextAttributes.GRAYED_ATTRIBUTES);
    }

    public void addNestedPom(PomNode child) {
      if (!modulePomsNode.addUnder(child)) {
        nonModulePomsNode.addUnder(child);
      }
    }

    void onFileUpdate() {
      final String oldName = getName();
      final String oldPath = savedPath;

      updateNode();

      if (!oldPath.equals(savedPath)) {
        removeFromParent();
        root.addToStructure(this);
      }
      else if (!oldName.equals(getName())) {
        PomGroupNode groupNode = getParent(PomGroupNode.class);
        groupNode.reinsert(this);
        updateTreeFrom(getVisibleParent());
      }
      else {
        updateSubTree();
      }
    }

    private ListNode getVisibleParent() {
      return getParent(getTreeViewSettings().groupByDirectory
                       ? NonModulePomsNode.class
                       : getTreeViewSettings().groupByModule ? ModuleNode.class : RootNode.class);
    }

    void removeFromParent() {
      // parent can be null if we are trying to remove an excluded node
      // thus not present in structure and has no parent
      PomGroupNode parent = getParent(PomGroupNode.class);
      if (parent != null) parent.remove(this);
    }

    public void unlinkNested() {
      modulePomsNode.clear();
      nonModulePomsNode.clear();
    }

    public boolean containsAsModule(PomNode node) {
      MavenProjectsManager m = MavenProjectsManager.getInstance(PomTreeStructure.this.project);
      return m.isModuleOf(project, node.project);
    }

    public void setIgnored(boolean on) {
      updateText();
      if (getTreeViewSettings().showIgnored) {
        updateSubTree();
      }
      else {
        if (on) {
          removeFromParent();
        }
        else {
          root.addToStructure(this);
        }
      }
    }

    private void createProfileNodes() {
      profilesNode.clear();
      for (String id : project.getAllProfiles()) {
        profilesNode.add(id);
      }
      profilesNode.updateActive(myProjectsManager.getActiveProfiles(project.getFile()), true);
    }

    public void setProfiles(final Collection<String> profiles) {
      profilesNode.updateActive(profiles, false);
      regroupNested();
      createPluginsNode();
      updateSubTree();
    }

    @Nullable
    public GoalNode findGoalNode(final String goalName) {
      for (GoalNode goalNode : collectGoalNodes()) {
        if (goalNode.getGoal().equals(goalName)) {
          return goalNode;
        }
      }

      return null;
    }

    public void updateShortcuts(@Nullable String actionId) {
      if (actionId == null || actionId.startsWith(actionIdPrefix)) {
        boolean update = false;
        for (GoalNode goalNode : collectGoalNodes()) {
          update = goalNode.updateShortcut(actionId) || update;
        }
        if (update) {
          updateSubTree();
        }
      }
    }

    private Collection<GoalNode> collectGoalNodes() {
      Collection<GoalNode> goalNodes = new ArrayList<GoalNode>(lifecycleNode.goalNodes);

      goalNodes.addAll(lifecycleNode.goalNodes);

      for (PluginNode pluginNode : pluginsNode.pluginNodes) {
        goalNodes.addAll(pluginNode.goalNodes);
      }

      return goalNodes;
    }

    public void regroupNested() {
      regroupMisplaced(modulePomsNode, false);
      regroupMisplaced(nonModulePomsNode, true);
    }

    private void regroupMisplaced(final NestedPomsNode src, final boolean misplacedFlag) {
      Collection<PomNode> misplaced = new ArrayList<PomNode>();
      for (PomNode node : src.pomNodes) {
        if (containsAsModule(node) == misplacedFlag) {
          misplaced.add(node);
        }
      }
      for (PomNode node : misplaced) {
        src.pomNodes.remove(node);
        addNestedPom(node);
      }
      if (!misplaced.isEmpty()) {
        updateSubTree();
      }
    }
  }

  public class NestedPomsNode extends PomGroupNode {

    private PomGroupNode sibling;

    public NestedPomsNode(PomNode parent) {
      super(parent);
      setIcons(iconModulesClosed, iconModulesOpen);
    }

    boolean isVisible() {
      return super.isVisible() && getTreeViewSettings().groupByDirectory;
    }

    @NotNull
    protected CustomNode getVisibleParent() {
      return getParent(PomNode.class);
    }

    protected Collection<PomNode> removeChildren(final PomNode node, final Collection<PomNode> children) {
      super.removeChildren(node, children);
      if (sibling != null) {
        sibling.removeOwnChildren(node, children);
      }
      return children;
    }
  }

  class ModulePomsNode extends NestedPomsNode {

    public ModulePomsNode(PomNode parent) {
      super(parent);
      addPlainText(NavigatorBundle.message("node.modules"));
    }

    public boolean addUnder(PomNode newNode) {
      if (getParent(PomNode.class).containsAsModule(newNode)) {
        add(newNode);
        return true;
      }
      return addUnderExisting(newNode);
    }

    protected PomGroupNode getNewParentForOrphans() {
      return getParent(PomNode.class).nonModulePomsNode;
    }
  }

  class NonModulePomsNode extends NestedPomsNode {

    public NonModulePomsNode(PomNode parent) {
      super(parent);
      addPlainText(NavigatorBundle.message("node.nested.poms"));
    }
  }

  abstract class GoalGroupNode extends ListNode {

    final List<GoalNode> goalNodes = new ArrayList<GoalNode>();
    private final PomNode pomNode;

    public GoalGroupNode(PomNode parent) {
      super(parent);
      pomNode = parent;
    }

    boolean isVisible() {
      return !isMinimalView();
    }

    void display(final DisplayList list) {
      if (isVisible()) {
        super.display(list);
      }
      else {
        displayChildren(list);
      }
    }

    protected void displayChildren(DisplayList displayList) {
      displayList.add(goalNodes);
    }
  }

  public abstract class GoalNode extends CustomNode {
    private final String goal;
    private final PomNode pomNode;

    private String actionId;

    public GoalNode(GoalGroupNode parent, String goal) {
      super(parent);
      this.goal = goal;
      pomNode = parent.pomNode;
      setUniformIcon(iconPhase);
      updateText();
    }

    private void updateText() {
      clearColoredText();
      addPlainText(goal);

      actionId = pomNode.actionIdPrefix + goal;
      if (!isMinimalView()) {
        String shortcut = myEventsHandler.getActionDescription(pomNode.savedPath, goal);
        if (shortcut != null) {
          addColoredFragment(" (" + shortcut + ")", SimpleTextAttributes.GRAY_ATTRIBUTES);
        }
      }
    }

    public boolean updateShortcut(@Nullable String actionId) {
      if (actionId == null || actionId.equals(this.actionId)) {
        updateText();
        return true;
      }
      return false;
    }

    public String getGoal() {
      return goal;
    }

    @Nullable
    @NonNls
    protected String getActionId() {
      return "Maven.Run";
    }

    @Nullable
    @NonNls
    protected String getMenuId() {
      return "Maven.GoalMenu";
    }
  }

  class LifecycleNode extends GoalGroupNode {

    public LifecycleNode(PomNode parent) {
      super(parent);
      addPlainText(NavigatorBundle.message("node.lifecycle"));
      setIcons(iconPhasesClosed, iconPhasesOpen);
    }

    public void updateGoals() {
      goalNodes.clear();
      for (String goal : standardGoals) {
        goalNodes.add(new StandardGoalNode(this, goal));
      }
    }
  }

  class StandardGoalNode extends GoalNode {

    public StandardGoalNode(GoalGroupNode parent, String goal) {
      super(parent, goal);
    }

    public boolean isVisible() {
      return !getTreeViewSettings().filterStandardPhases || standardPhases.contains(getName());
    }
  }

  private class ProfilesNode extends ListNode {
    final List<ProfileNode> profileNodes = new ArrayList<ProfileNode>();

    public ProfilesNode(final PomNode parent) {
      super(parent);
      addPlainText(NavigatorBundle.message("node.profiles"));
      setIcons(iconProfilesClosed, iconProfilesOpen);
    }

    boolean isVisible() {
      return !profileNodes.isEmpty() && !isMinimalView();
    }

    protected void displayChildren(DisplayList displayList) {
      displayList.add(profileNodes);
    }

    public void clear() {
      profileNodes.clear();
    }

    public void add(final String profileName) {
      insertSorted(profileNodes, new ProfileNode(this, profileName));
    }

    public void updateActive(final Collection<String> activeProfiles, boolean force) {
      for (ProfileNode node : profileNodes) {
        final boolean active = activeProfiles.contains(node.getProfile());
        if (active != node.isActive() || force) {
          node.setActive(active);
        }
      }
    }
  }

  public class ProfileNode extends CustomNode {
    private final String name;
    private boolean active;

    public ProfileNode(final ProfilesNode parent, String name) {
      super(parent);
      this.name = name;
      addPlainText(name);
    }

    public String getProfile() {
      return name;
    }

    @Nullable
    @NonNls
    protected String getActionId() {
      return "Maven.ToggleProfile";
    }

    @Nullable
    @NonNls
    protected String getMenuId() {
      return "Maven.ProfileMenu";
    }

    public boolean isActive() {
      return active;
    }

    private void setActive(final boolean active) {
      this.active = active;
    }
  }

  public class DependenciesNode extends ListNode {
    final List<DependencyNode> children = new ArrayList<DependencyNode>();

    public DependenciesNode(final PomNode parent) {
      super(parent);
      addPlainText(NavigatorBundle.message("node.dependencies"));
      setIcons(iconDependenciesClosed, iconDependenciesOpen);
    }

    boolean isVisible() {
      return !children.isEmpty() && !isMinimalView();
    }

    protected void displayChildren(DisplayList displayList) {
      displayList.add(children);
    }

    public void clear() {
      children.clear();
    }

    public void add(DependencyNode node) {
      insertSorted(children, node);
    }
  }

  private class DependencyNode extends CustomNode {
    public DependencyNode(CustomNode parent, MavenId id) {
      super(parent);
      addPlainText(id.toString());
      setUniformIcon(iconDependency);
    }
  }

  private class PluginsNode extends ListNode {
    final List<PluginNode> pluginNodes = new ArrayList<PluginNode>();

    public PluginsNode(final PomNode parent) {
      super(parent);
      addPlainText(NavigatorBundle.message("node.plugins"));
      setIcons(iconPluginsClosed, iconPluginsOpen);
    }

    boolean isVisible() {
      return !pluginNodes.isEmpty() && !isMinimalView();
    }

    protected void displayChildren(DisplayList displayList) {
      displayList.add(pluginNodes);
    }

    public void clear() {
      pluginNodes.clear();
    }

    public void add(PluginNode node) {
      insertSorted(pluginNodes, node);
    }
  }

  public class PluginNode extends GoalGroupNode {
    private final MavenId myId;

    public PluginNode(PomNode parent, final MavenId id) {
      super(parent);
      myId = id;
      setUniformIcon(iconPlugin);

      MavenPluginInfo plugin = getRepository().loadPluginInfo(id);
      if (plugin == null) {
        addColoredFragment(id.toString(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_WAVED, Color.red));
      }
      else {
        addPlainText(plugin.getGoalPrefix());
        addColoredFragment(" (" + getId().toString() + ")", SimpleTextAttributes.GRAY_ATTRIBUTES);
        for (MavenPluginInfo.Mojo mojo : plugin.getMojos()) {
          goalNodes.add(new PluginGoalNode(this, mojo.getQualifiedGoal()));
        }
      }
    }

    public MavenId getId() {
      return myId;
    }
  }

  class PluginGoalNode extends GoalNode {
    public PluginGoalNode(PluginNode parent, String goal) {
      super(parent, goal);
      setUniformIcon(iconPluginGoal);
    }
  }

  public class ExtensionsNode extends ListNode {
    final List<ExtensionNode> children = new ArrayList<ExtensionNode>();

    public ExtensionsNode(final PomNode parent) {
      super(parent);
      addPlainText(NavigatorBundle.message("node.extensions"));
      setIcons(iconExtensionsClosed, iconExtensionsOpen);
    }

    boolean isVisible() {
      return !children.isEmpty() && !isMinimalView();
    }

    protected void displayChildren(DisplayList displayList) {
      displayList.add(children);
    }

    public void clear() {
      children.clear();
    }

    public void add(ExtensionNode node) {
      insertSorted(children, node);
    }
  }

  private class ExtensionNode extends CustomNode {
    public ExtensionNode(CustomNode parent, MavenId id) {
      super(parent);
      addPlainText(id.toString());
      setUniformIcon(iconExtension);
    }
  }
}
