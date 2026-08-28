package com.comics8.monitor.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comics8.core.model.ProxyType
import com.comics8.monitor.ui.ListingUiState
import com.comics8.monitor.ui.ListingViewModel
import com.comics8.monitor.ui.theme.LocalStrings

@Composable
fun NetworkSettingsPane(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val net = state.networkSettings
    var customHost by remember(net.customProxy.host) { mutableStateOf(net.customProxy.host) }
    var customPort by remember(net.customProxy.port) { mutableStateOf(net.customProxy.port.toString()) }
    var customUser by remember(net.customProxy.username) { mutableStateOf(net.customProxy.username) }
    var customPass by remember(net.customProxy.password) { mutableStateOf(net.customProxy.password) }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        SectionTitle(title = strings.sectionNetwork, icon = Icons.Default.Language)

        SettingCard {
            // 1. 직접 연결
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setProxyType(ProxyType.DIRECT) },
            ) {
                RadioButton(
                    selected = net.proxyType == ProxyType.DIRECT,
                    onClick = { viewModel.setProxyType(ProxyType.DIRECT) },
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.labelProxyDirect,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = strings.descProxyDirect,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            // 2. 서버 IP 경유
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setProxyType(ProxyType.SERVER) },
            ) {
                RadioButton(
                    selected = net.proxyType == ProxyType.SERVER,
                    onClick = { viewModel.setProxyType(ProxyType.SERVER) },
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.labelProxyServer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = strings.descProxyServer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            // 3. 사용자 지정 프록시
            val isCustom = net.proxyType == ProxyType.CUSTOM_HTTP || net.proxyType == ProxyType.CUSTOM_SOCKS
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!isCustom) viewModel.setProxyType(ProxyType.CUSTOM_HTTP)
                    },
            ) {
                RadioButton(
                    selected = isCustom,
                    onClick = {
                        if (!isCustom) viewModel.setProxyType(ProxyType.CUSTOM_HTTP)
                    },
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.labelProxyCustom,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = strings.descProxyCustom,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 사용자 지정 프록시 세부 입력 폼 (점진적 공개)
            if (isCustom) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, top = 4.dp),
                ) {
                    // 프로토콜 선택 (HTTP vs SOCKS5)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = net.proxyType == ProxyType.CUSTOM_HTTP,
                            onClick = { viewModel.setProxyType(ProxyType.CUSTOM_HTTP) },
                            label = { Text("HTTP") },
                        )
                        FilterChip(
                            selected = net.proxyType == ProxyType.CUSTOM_SOCKS,
                            onClick = { viewModel.setProxyType(ProxyType.CUSTOM_SOCKS) },
                            label = { Text("SOCKS5") },
                        )
                    }

                    // Host & Port
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = customHost,
                            onValueChange = {
                                customHost = it
                                viewModel.setCustomProxy(net.customProxy.copy(host = it))
                            },
                            label = { Text(strings.labelProxyHost) },
                            placeholder = { Text("127.0.0.1") },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                        )
                        OutlinedTextField(
                            value = customPort,
                            onValueChange = {
                                customPort = it.filter { c -> c.isDigit() }
                                val p = customPort.toIntOrNull() ?: 1080
                                viewModel.setCustomProxy(net.customProxy.copy(port = p))
                            },
                            label = { Text(strings.labelProxyPort) },
                            placeholder = { Text("1080") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Username & Password
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = customUser,
                            onValueChange = {
                                customUser = it
                                viewModel.setCustomProxy(net.customProxy.copy(username = it))
                            },
                            label = { Text(strings.labelProxyUser) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = customPass,
                            onValueChange = {
                                customPass = it
                                viewModel.setCustomProxy(net.customProxy.copy(password = it))
                            },
                            label = { Text(strings.labelProxyPassword) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Test Connection Button & Result
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.testProxyConnection(strings) },
                            enabled = !state.proxyTesting && customHost.isNotBlank(),
                        ) {
                            if (state.proxyTesting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(strings.actionTestProxy)
                        }

                        if (state.proxyTestResult != null) {
                            Text(
                                text = state.proxyTestResult!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (state.proxyTestSuccess) {
                                    true -> MaterialTheme.colorScheme.primary
                                    false -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
