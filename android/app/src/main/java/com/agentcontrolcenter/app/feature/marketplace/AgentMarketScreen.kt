package com.agentcontrolcenter.app.feature.marketplace

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.agentcontrolcenter.app.ui.theme.ShapeS10
import com.agentcontrolcenter.app.ui.theme.ShapeS12
import com.agentcontrolcenter.app.ui.theme.ShapeXs4
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.data.marketplace.MarketplaceClient
import com.agentcontrolcenter.app.data.marketplace.MarketplaceFavoriteRepository
import com.agentcontrolcenter.app.agent.model.AgentType
import com.agentcontrolcenter.app.agent.model.AgentTypeUi
import com.agentcontrolcenter.app.data.model.MarketplaceAgent
import com.agentcontrolcenter.app.ui.adaptive.currentAdaptiveConfig
import com.agentcontrolcenter.app.ui.theme.AppCard
import com.agentcontrolcenter.app.ui.theme.AppTopAppBar
import dagger.hilt.android.EntryPointAccessors
import java.text.NumberFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
@kotlin.Suppress("unused")
interface MarketplaceFavoriteEntryPoint {
    fun favoriteRepository(): MarketplaceFavoriteRepository
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentMarketScreen(
    onInstall: (MarketplaceAgent) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val adaptive = currentAdaptiveConfig()

    var agents by remember { mutableStateOf<List<MarketplaceAgent>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var installedIds by remember { mutableStateOf(setOf<String>()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val marketClient = remember { MarketplaceClient() }

    // v4.9.0: 收藏状态由 MarketplaceFavoriteRepository（Hilt @Singleton）驱动。
    // 通过 EntryPoint 注入，避免破坏屏幕现有签名。
    val entryPoint = remember {
        EntryPointAccessors.fromApplication<MarketplaceFavoriteEntryPoint>(
            context.applicationContext
        )
    }
    val favoriteRepository = remember { entryPoint.favoriteRepository() }
    val scope = rememberCoroutineScope()
    val favoriteIds by favoriteRepository.favoriteIdsFlow()
        .collectAsStateWithLifecycle(initialValue = emptySet())
    var favoritesOnly by remember { mutableStateOf(false) }

    // Load agents from live OpenClaw + ClawHub APIs
    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        val result = marketClient.fetchAll()
        if (result.isSuccess) {
            agents = result.getOrNull() ?: emptyList()
            errorMessage = null
        } else {
            // 网络失败时不再回退到写死的本地假数据，仅展示错误并允许重试
            agents = emptyList()
            errorMessage = result.exceptionOrNull()?.message ?: "Network error"
        }
        isLoading = false
    }

    // Debounced search — re-fetch from API when query changes.
    // 真正的防抖实现：searchQuery 每次变化都会取消上一个 LaunchedEffect 协程，
    // 因此 delay(300) 只有在用户停止输入 300ms 后才会真正执行后续网络请求，
    // 避免每次按键都触发一次 API 调用。
    LaunchedEffect(searchQuery) {
        delay(300)
        if (searchQuery.length < 2 && searchQuery.isNotEmpty()) return@LaunchedEffect
        isLoading = true
        errorMessage = null
        val result = marketClient.fetchAll(searchQuery.takeIf { it.isNotBlank() })
        if (result.isSuccess) {
            agents = result.getOrNull() ?: emptyList()
        } else {
            errorMessage = result.exceptionOrNull()?.message
        }
        isLoading = false
    }

    val allTags = remember(agents) {
        agents.flatMap { it.tags }.distinct().sorted()
    }

    val filteredAgents = remember(agents, searchQuery, selectedTag, favoritesOnly, favoriteIds) {
        agents.filter { agent ->
            val matchesSearch = searchQuery.isBlank() ||
                agent.name.contains(searchQuery, ignoreCase = true) ||
                agent.description.contains(searchQuery, ignoreCase = true) ||
                agent.author.contains(searchQuery, ignoreCase = true)
            val matchesTag = selectedTag == null || agent.tags.contains(selectedTag)
            val matchesFavorite = !favoritesOnly || favoriteIds.contains(agent.id)
            matchesSearch && matchesTag && matchesFavorite
        }
    }

    Scaffold(
        topBar = {
            AppTopAppBar(
                title = { Text(stringResource(R.string.marketplace_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_close))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.marketplace_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_close))
                        }
                    }
                },
                singleLine = true,
                shape = ShapeS12
            )

            // Tag filter chips
            if (allTags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Show limited tags in a scrollable-like manner
                    FilterChip(
                        selected = selectedTag == null,
                        onClick = { selectedTag = null },
                        label = { Text(stringResource(R.string.marketplace_all)) }
                    )
                    allTags.take(4).forEach { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = { selectedTag = if (selectedTag == tag) null else tag },
                            label = { Text(tag) }
                        )
                    }
                    // v4.9.0: 收藏筛选 chip — 仅展示已收藏 Agent
                    FilterChip(
                        selected = favoritesOnly,
                        onClick = { favoritesOnly = !favoritesOnly },
                        label = {
                            Text(stringResource(R.string.marketplace_favorites))
                        },
                        leadingIcon = {
                            Icon(
                                if (favoritesOnly) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Results count
            Text(
                text = stringResource(R.string.marketplace_results, filteredAgents.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // Agent list
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.marketplace_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            } else if (errorMessage != null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.WifiOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            } else if (filteredAgents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (favoritesOnly) Icons.Default.BookmarkBorder else Icons.Default.Storefront,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(
                                if (favoritesOnly) R.string.marketplace_no_favorites
                                else R.string.marketplace_no_results
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredAgents, key = { it.id }) { agent ->
                        MarketplaceAgentCard(
                            agent = agent,
                            isInstalled = installedIds.contains(agent.id),
                            isFavorite = favoriteIds.contains(agent.id),
                            onInstall = {
                                installedIds = installedIds + agent.id
                                onInstall(agent)
                            },
                            onToggleFavorite = {
                                scope.launch { favoriteRepository.toggle(agent) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketplaceAgentCard(
    agent: MarketplaceAgent,
    isInstalled: Boolean,
    isFavorite: Boolean,
    onInstall: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeS12,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Agent type icon
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = ShapeS10,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            AgentTypeUi.icon(agent.type),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${agent.type.displayName} · ${agent.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // v4.9.0: 收藏书签图标
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = stringResource(
                            if (isFavorite) R.string.marketplace_unfavorite
                            else R.string.marketplace_favorite
                        ),
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // Install button
                if (isInstalled) {
                    FilledTonalButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.marketplace_installed), style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.marketplace_install), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Description
            Text(
                text = agent.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Stats row: rating + downloads + tags（仅在有真实数据时展示统计）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Rating（仅当 API 真实提供时）
                agent.rating?.let { rating ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = String.format("%.1f", rating),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                // Downloads（仅当 API 真实提供时）
                agent.downloads?.let { downloads ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = NumberFormat.getIntegerInstance().format(downloads),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                // Tags
                agent.tags.take(3).forEach { tag ->
                    Surface(
                        shape = ShapeXs4,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
