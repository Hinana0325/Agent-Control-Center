package com.agentcontrolcenter.app.navigation

import com.agentcontrolcenter.app.R
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.agentcontrolcenter.app.ui.theme.AppNavigationBar
import com.agentcontrolcenter.app.ui.theme.AppNavigationRail
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agentcontrolcenter.app.feature.activity.ActivityScreen
import com.agentcontrolcenter.app.ui.adaptive.currentAdaptiveConfig
import com.agentcontrolcenter.app.ui.adaptive.shouldShowRail
import com.agentcontrolcenter.app.ui.components.PredictiveBackWrapper
import com.agentcontrolcenter.app.ui.components.LocalNavAnimatedVisibilityScope
import com.agentcontrolcenter.app.ui.components.LocalSharedTransitionScope
import com.agentcontrolcenter.app.ui.components.LocalSnackbarHost
import com.agentcontrolcenter.app.feature.agents.AgentsScreen
import com.agentcontrolcenter.app.feature.chat.ChatScreen
import com.agentcontrolcenter.app.feature.sessions.SessionsScreen
import com.agentcontrolcenter.app.feature.settings.SettingsScreen
import com.agentcontrolcenter.app.feature.compare.CompareScreen
import com.agentcontrolcenter.app.feature.compare.CompareViewModel
import com.agentcontrolcenter.app.feature.marketplace.AgentMarketScreen
import com.agentcontrolcenter.app.feature.sync.DeviceSyncScreen
import com.agentcontrolcenter.app.feature.plugin.PluginScreen
import com.agentcontrolcenter.app.feature.insights.InsightsScreen
import com.agentcontrolcenter.app.feature.task.TasksScreen
import com.agentcontrolcenter.app.feature.mcp.McpScreen
import com.agentcontrolcenter.app.feature.workflow.WorkflowScreen
import com.agentcontrolcenter.app.data.model.MarketplaceAgent
import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.agent.model.AgentType
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val adaptive = currentAdaptiveConfig()
    val chatViewModel: com.agentcontrolcenter.app.feature.chat.ChatViewModel = hiltViewModel()

    // P2: 全局 SnackbarHostState。在根 Scaffold 上挂载 SnackbarHost，
    // 通过 LocalSnackbarHost 向下提供给所有 Composable，替代散落各处的 Toast。
    val snackbarHostState = remember { SnackbarHostState() }

    // P3-5: 观察 Launcher 快捷方式请求，导航到对应目的地后消费
    val pendingShortcutAction by ShortcutRouter.pendingAction.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShortcutAction) {
        val action = pendingShortcutAction ?: return@LaunchedEffect
        val targetRoute = when (action) {
            ShortcutRouter.Action.NEW_CHAT -> Screen.Chat.route
            ShortcutRouter.Action.NEW_AGENT -> Screen.Agents.route
            ShortcutRouter.Action.SETTINGS -> Screen.Settings.route
        }
        navController.navigate(targetRoute) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
        ShortcutRouter.consume()
    }

    // E1: 用 PredictiveBackWrapper 包裹整个顶层导航容器，
    // 让预测性返回手势（Android 14+）触发当前页面缩放 + 淡出动画。
    // - 栈底（previousBackStackEntry == null）时禁用，交还给系统处理（如退出 App）。
    // - 手势进行中：内容随手指滑动缩小到 0.9、淡出到 0.7。
    // - 手势提交：淡出到 0 后执行 popBackStack。
    // - 手势取消：spring 平滑恢复到原始状态。
    PredictiveBackWrapper(
        enabled = navController.previousBackStackEntry != null,
        onBack = { navController.popBackStack() }
    ) {
        // P2: 把全局 snackbarHostState 提供给整个子树（含两个 Scaffold 分支），
        // 子 Composable 通过 LocalSnackbarHost.current.showSnackbar() 即可在
        // 当前生效的根 Scaffold 上展示消息。
        CompositionLocalProvider(LocalSnackbarHost provides snackbarHostState) {
            if (adaptive.shouldShowRail) {
                Row(modifier = Modifier.fillMaxSize()) {
                    AppNavigationRail(
                        header = { Spacer(modifier = Modifier.height(24.dp)) }
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Screen.getTabs().forEach { screen ->
                            val selected = currentDestination?.hierarchy?.any {
                                it.route == screen.route
                            } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(imageVector = screen.icon, contentDescription = stringResource(screen.stringResId)) },
                                label = { Text(text = stringResource(screen.stringResId)) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }

                    // I1: 折叠屏铰链避让。
                    // book mode 下铰链竖向分隔屏幕，Rail 在左、内容在右，
                    // 中间用 hingeWidth 宽度的 Spacer 避开铰链区域，
                    // 防止内容被铰链遮挡或落在不可点击区域。
                    // 非折叠屏 / 全开时 hingeWidth = 0.dp，Spacer 退化为无宽度。
                    if (adaptive.isBookMode && adaptive.hingeWidth > 0.dp) {
                        Spacer(modifier = Modifier.width(adaptive.hingeWidth))
                    }

                    Scaffold(
                        modifier = Modifier.weight(1f),
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top)
                    ) { paddingValues ->
                        AppNavHost(
                            navController = navController,
                            chatViewModel = chatViewModel,
                            modifier = Modifier.padding(paddingValues).navigationBarsPadding()
                        )
                    }
                }
            } else {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom),
                    bottomBar = {
                        AppNavigationBar {
                            Screen.getTabs().forEach { screen ->
                                val selected = currentDestination?.hierarchy?.any {
                                    it.route == screen.route
                                } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(imageVector = screen.icon, contentDescription = stringResource(screen.stringResId)) },
                                    label = { Text(text = stringResource(screen.stringResId), style = MaterialTheme.typography.labelSmall) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                ) { paddingValues ->
                    AppNavHost(
                        navController = navController,
                        chatViewModel = chatViewModel,
                        modifier = Modifier.padding(paddingValues)
                    )
                }
            }
        }
    }
}

/**
 * Shared NavHost used by both the tablet (NavigationRail) and phone (BottomBar) layouts.
 * Extracted to avoid duplicating the entire destination graph between the two branches.
 *
 * 使用 Android 16 Material 3 Expressive Spring Motion 转场动画：
 * - 进入：spring(dampingRatio=0.8, stiffness=MediumLow) — 自然弹入
 * - 退出：spring(dampingRatio=0.9, stiffness=Medium) — 平滑退出
 * - popEnter/popExit 对称配置，保证返回手势一致
 *
 * E2: 通过 SharedTransitionLayout 包裹 NavHost，为页面间共享元素动画提供
 * SharedTransitionScope；每个 composable 内通过 CompositionLocalProvider
 * 向下传递当前页面的 AnimatedVisibilityScope（即 composable lambda 的 this），
 * 供 Modifier.sharedBounds() 使用。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    chatViewModel: com.agentcontrolcenter.app.feature.chat.ChatViewModel,
    modifier: Modifier = Modifier
) {
    // M3 Expressive Spring Motion specs
    // 使用公开的 fadeIn/slideInHorizontally + spring animationSpec
    val springEnter: androidx.compose.animation.EnterTransition = androidx.compose.animation.fadeIn(
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        )
    ) + androidx.compose.animation.slideInHorizontally(
        initialOffsetX = { it / 8 },
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        )
    )
    val springExit: androidx.compose.animation.ExitTransition = androidx.compose.animation.fadeOut(
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        )
    ) + androidx.compose.animation.slideOutHorizontally(
        targetOffsetX = { -it / 8 },
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        )
    )

    // SharedTransitionLayout 的 content lambda 接收者为 SharedTransitionScope，
    // 因此 `this` 即为共享元素动画作用域，通过 CompositionLocal 向下传递。
    SharedTransitionLayout(modifier = modifier) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = Screen.Chat.route,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { springEnter },
                exitTransition = { springExit },
                popEnterTransition = { springEnter },
                popExitTransition = { springExit }
            ) {
                composable(Screen.Chat.route) {
                    // composable lambda 的 this 是 AnimatedContentScope，
                    // 它实现了 AnimatedVisibilityScope，可直接 provides。
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        ChatScreen(
                            viewModel = chatViewModel,
                            navController = navController,
                            onNavigateToSessions = { navController.navigate(Screen.Sessions.route) },
                            onNavigateToTasks = { navController.navigate(Screen.Tasks.route) }
                        )
                    }
                }
                composable(Screen.Sessions.route) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        SessionsScreen(
                            chatViewModel = chatViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                composable(Screen.Activity.route) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        ActivityScreen(
                            onNavigate = { route -> navController.navigate(route) }
                        )
                    }
                }
                composable(Screen.Settings.route) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        SettingsScreen(
                            onNavigate = { route -> navController.navigate(route) }
                        )
                    }
                }
                composable(Screen.Agents.route) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        AgentsScreen(
                            onNavigate = { route -> navController.navigate(route) }
                        )
                    }
                }
                composable(Screen.Marketplace.route) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        AgentMarketScreen(
                            onInstall = { agent ->
                                val config = AgentConfig(
                                    id = agent.id,
                                    name = agent.name,
                                    type = agent.type,
                                    serverUrl = agent.serverUrl
                                )
                                chatViewModel.installMarketplaceAgent(config)
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                composable(Screen.Insights.route) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        InsightsScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Screen.DeviceSync.route) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        DeviceSyncScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Screen.Plugins.route) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        PluginScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Screen.Tasks.route) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        TasksScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Screen.Mcp.route) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        McpScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Screen.Compare.route) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        val compareViewModel: CompareViewModel = hiltViewModel()
                        CompareScreen(viewModel = compareViewModel, onBack = { navController.popBackStack() })
                    }
                }
                // v5.1: More Tab 移除 — 次级入口按功能归属分散到对应 Tab：
                // Workflow/Plugins/Mcp/Compare → Agents Tab 溢出菜单；
                // Insights → Activity Tab；DeviceSync → Settings「同步」分类。
                composable(Screen.Workflow.route) {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        WorkflowScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
