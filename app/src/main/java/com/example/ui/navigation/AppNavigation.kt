package com.example.ui.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.ai.brain.MasterAiBrain
import com.example.ai.provider.BackendAiProxyClient
import com.example.ai.provider.BackendProxyAiProvider
import com.example.ai.provider.GeminiFirebaseProvider
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.ModelRouter
import com.example.ai.provider.MultiAiOrchestrator
import com.example.ai.provider.ProviderId
import com.example.build.BuildServerClient
import com.example.builder.AppBuilderOrchestrator
import com.example.coding.CodingAgentOrchestrator
import com.example.core.security.BackendUrlLocalStore
import com.example.core.security.authStateFlow
import com.example.data.repository.SuperBrainRepository
import com.example.ui.architecture.ArchitectureStudioScreen
import com.example.ui.auth.AuthScreen
import com.example.ui.brain.BrainConsoleScreen
import com.example.ui.brain.SuperBrainViewModel
import com.example.ui.builder.AppBuilderStudioScreen
import com.example.ui.projects.ProjectSettingsScreen
import com.example.ui.projects.ProjectsMemoryScreen
import com.example.ui.providers.ModelHubScreen
import com.example.ui.theme.CosmicSurface
import com.example.ui.theme.CyberBlue
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeonPurpleBright
import com.example.ui.theme.ObsidianBg
import com.example.ui.workspace.CodingWorkspaceScreen
import com.example.ui.workspace.EngineeringStudioViewModel
import com.example.ui.workspace.WorkspaceSubTab
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.atomic.AtomicReference

const val ROUTE_BRAIN = "brain_console"
const val ROUTE_PROVIDERS = "model_hub"
const val ROUTE_APP_STUDIO = "app_studio"
const val ROUTE_WORKSPACE = "coding_workspace"
const val ROUTE_PROJECTS = "project_memory"
const val ROUTE_BUILD_CONSOLE = "build_console"
const val ROUTE_SETTINGS = "project_settings"
const val ROUTE_ARCHITECTURE = "architecture_studio"

private data class NavDestinationItem(
    val route: String,
    val labelResId: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val TOP_DESTINATIONS = listOf(
    NavDestinationItem(ROUTE_BRAIN, R.string.nav_brain, Icons.Filled.Psychology, Icons.Outlined.Psychology, "nav_tab_brain"),
    NavDestinationItem(ROUTE_PROVIDERS, R.string.nav_providers, Icons.Filled.Hub, Icons.Outlined.Hub, "nav_tab_providers"),
    NavDestinationItem(ROUTE_APP_STUDIO, R.string.nav_app_studio, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome, "nav_tab_app_studio"),
    NavDestinationItem(ROUTE_WORKSPACE, R.string.nav_workspace, Icons.Filled.Code, Icons.Outlined.Code, "nav_tab_workspace"),
    NavDestinationItem(ROUTE_PROJECTS, R.string.nav_projects, Icons.Filled.FolderSpecial, Icons.Outlined.FolderSpecial, "nav_tab_projects")
)

private data class StudioDiContainer(
    val repository: SuperBrainRepository,
    val registry: ModelCapabilityRegistry,
    val router: ModelRouter,
    val brain: MasterAiBrain,
    val multiAiOrchestrator: MultiAiOrchestrator,
    val codingOrchestrator: CodingAgentOrchestrator,
    val appBuilderOrchestrator: AppBuilderOrchestrator,
    val buildServerClient: BuildServerClient
)

@Composable
fun AppNavigation(
    auth: FirebaseAuth? = null
) {
    val resolvedAuth = remember(auth) { auth ?: Firebase.auth }
    val currentUser by resolvedAuth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = resolvedAuth.currentUser)

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            AuthenticatedSuperBrainShell(
                currentUserId = user.uid,
                userDisplayName = user.displayName ?: "Commander",
                userEmail = user.email.orEmpty()
            )
        }
    }
}

@Composable
private fun AuthenticatedSuperBrainShell(
    currentUserId: String,
    userDisplayName: String,
    userEmail: String
) {
    val context = LocalContext.current.applicationContext
    val backendUrlRef = remember { AtomicReference("") }
    val di = remember(currentUserId, context) {
        val databaseId = context.getString(R.string.firestore_database_id)
        val db = FirebaseFirestore.getInstance(databaseId)
        val repository = SuperBrainRepository(db, Firebase.auth)
        val proxyClient = BackendAiProxyClient()
        val registry = ModelCapabilityRegistry(
            listOf(
                GeminiFirebaseProvider(),
                BackendProxyAiProvider(ProviderId.GROQ, proxyClient) { backendUrlRef.get() },
                BackendProxyAiProvider(ProviderId.OPENROUTER_FREE, proxyClient) { backendUrlRef.get() },
                BackendProxyAiProvider(ProviderId.MISTRAL, proxyClient) { backendUrlRef.get() },
                BackendProxyAiProvider(ProviderId.COHERE, proxyClient) { backendUrlRef.get() },
                BackendProxyAiProvider(ProviderId.CLOUDFLARE_AI, proxyClient) { backendUrlRef.get() },
                BackendProxyAiProvider(ProviderId.NVIDIA_NIM, proxyClient) { backendUrlRef.get() },
                BackendProxyAiProvider(ProviderId.SAMBANOVA, proxyClient) { backendUrlRef.get() },
                BackendProxyAiProvider(ProviderId.ANTHROPIC_CLAUDE, proxyClient) { backendUrlRef.get() },
                BackendProxyAiProvider(ProviderId.OPENAI_GPT, proxyClient) { backendUrlRef.get() },
                BackendProxyAiProvider(ProviderId.DEEPSEEK, proxyClient) { backendUrlRef.get() },
                BackendProxyAiProvider(ProviderId.META_LLAMA, proxyClient) { backendUrlRef.get() }
            )
        )
        val router = ModelRouter(registry)
        val brain = MasterAiBrain(registry, router)
        val multiAi = MultiAiOrchestrator(registry, proxyClient)
        val coding = CodingAgentOrchestrator(registry)
        val builder = AppBuilderOrchestrator(registry, coding)
        val buildClient = BuildServerClient()
        StudioDiContainer(
            repository = repository,
            registry = registry,
            router = router,
            brain = brain,
            multiAiOrchestrator = multiAi,
            codingOrchestrator = coding,
            appBuilderOrchestrator = builder,
            buildServerClient = buildClient
        )
    }

    val viewModel: SuperBrainViewModel = viewModel(
        key = "brain_$currentUserId",
        factory = viewModelFactory {
            initializer {
                SuperBrainViewModel(
                    repository = di.repository,
                    masterAiBrain = di.brain,
                    registry = di.registry,
                    currentUserId = currentUserId,
                    currentUserDisplayName = userDisplayName
                )
            }
        }
    )

    val backendUrlLocalStore = remember(context) { BackendUrlLocalStore(context) }

    val studioViewModel: EngineeringStudioViewModel = viewModel(
        key = "studio_$currentUserId",
        factory = viewModelFactory {
            initializer {
                EngineeringStudioViewModel(
                    repository = di.repository,
                    registry = di.registry,
                    multiAiOrchestrator = di.multiAiOrchestrator,
                    codingOrchestrator = di.codingOrchestrator,
                    appBuilderOrchestrator = di.appBuilderOrchestrator,
                    buildServerClient = di.buildServerClient,
                    activeProjectFlow = viewModel.activeProject,
                    workspaceStateFlow = viewModel.workspaceState,
                    onActivateProject = { pid -> viewModel.selectProject(pid) },
                    currentUserId = currentUserId,
                    backendUrlLocalStore = backendUrlLocalStore
                )
            }
        }
    )

    val studioUiState by studioViewModel.uiState.collectAsStateWithLifecycle()
    backendUrlRef.set(studioUiState.backendUrlDraft)

    val consoleState by viewModel.consoleState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: ROUTE_BRAIN
    val snackbarHostState = remember { SnackbarHostState() }

    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = ObsidianBg,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(
                        containerColor = CosmicSurface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        TOP_DESTINATIONS.forEach { dest ->
                            val selected = currentRoute == dest.route
                            val label = stringResource(dest.labelResId)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = {
                                    Text(
                                        text = label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CyberBlue,
                                    selectedTextColor = NeonPurpleBright,
                                    indicatorColor = NeonPurple.copy(alpha = 0.22f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = CosmicSurface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        TOP_DESTINATIONS.forEach { dest ->
                            val selected = currentRoute == dest.route
                            val label = stringResource(dest.labelResId)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label, maxLines = 1) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = CyberBlue,
                                    selectedTextColor = NeonPurpleBright,
                                    indicatorColor = NeonPurple.copy(alpha = 0.22f)
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_BRAIN,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(ROUTE_BRAIN) {
                        BrainConsoleScreen(
                            viewModel = viewModel,
                            snackbarHostState = snackbarHostState,
                            onOpenProjectsTab = { navigateToTab(ROUTE_PROJECTS) }
                        )
                    }
                    composable(ROUTE_PROVIDERS) {
                        ModelHubScreen(
                            viewModel = viewModel,
                            studioViewModel = studioViewModel,
                            onOpenProjectSettings = { navController.navigate(ROUTE_SETTINGS) }
                        )
                    }
                    composable(ROUTE_APP_STUDIO) {
                        AppBuilderStudioScreen(
                            studioViewModel = studioViewModel,
                            selectedLanguage = consoleState.selectedLanguage,
                            onNavigateToWorkspace = { navigateToTab(ROUTE_WORKSPACE) }
                        )
                    }
                    composable(ROUTE_WORKSPACE) {
                        CodingWorkspaceScreen(
                            studioViewModel = studioViewModel,
                            selectedLanguage = consoleState.selectedLanguage,
                            onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                            initialSubTab = WorkspaceSubTab.CODE_EDITOR
                        )
                    }
                    composable(ROUTE_PROJECTS) {
                        ProjectsMemoryScreen(
                            viewModel = viewModel,
                            onResumeInBrain = { navigateToTab(ROUTE_BRAIN) },
                            onOpenWorkspace = { navigateToTab(ROUTE_WORKSPACE) },
                            onOpenBuildConsole = { navController.navigate(ROUTE_BUILD_CONSOLE) },
                            onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                            onOpenArchitectureStudio = { navController.navigate(ROUTE_ARCHITECTURE) }
                        )
                    }
                    composable(ROUTE_BUILD_CONSOLE) {
                        CodingWorkspaceScreen(
                            studioViewModel = studioViewModel,
                            selectedLanguage = consoleState.selectedLanguage,
                            onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                            initialSubTab = WorkspaceSubTab.APK_BUILD,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable(ROUTE_SETTINGS) {
                        ProjectSettingsScreen(
                            studioViewModel = studioViewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable(ROUTE_ARCHITECTURE) {
                        ArchitectureStudioScreen(
                            userDisplayName = userDisplayName,
                            userEmail = userEmail,
                            userId = currentUserId,
                            onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
