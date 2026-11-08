
package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.navigation.NewProfileArgs
import io.nekohasekai.sfa.freeroute.FreeRouteCatalog
import io.nekohasekai.sfa.freeroute.FreeRouteNode
import kotlinx.coroutines.launch

@Composable
fun FreeRoutePanel(
    onOpenNewProfile: (NewProfileArgs) -> Unit,
) {
    var subscriptionUrl by remember { mutableStateOf("") }
    var manualLink by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf<List<FreeRouteNode>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf("برای دریافت فهرست، اسکن سرورها را بزنید.")
    }

    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("FreeRoute", style = MaterialTheme.typography.titleLarge)

            Text(
                "سرورهای عمومی رایگان",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                onClick = {
                    scope.launch {
                        loading = true
                        status = "در حال دریافت فهرست و بررسی پاسخ‌گویی..."

                        try {
                            val found = FreeRouteCatalog.discover().take(120)
                            nodes = FreeRouteCatalog.testAll(found)

                            val reachable = nodes.count {
                                it.latencyMs != null
                            }

                            status =
                                "پیدا شده: ${nodes.size} | پاسخ‌گو: $reachable"
                        } catch (error: Exception) {
                            status =
                                "خطا: ${error.message ?: "دریافت فهرست ناموفق بود"}"
                            nodes = emptyList()
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator()
                } else {
                    Text("اسکن سرورهای رایگان")
                }
            }

            Text(status, style = MaterialTheme.typography.bodySmall)

            if (nodes.isNotEmpty()) {
                Text(
                    "فهرست بر اساس کمترین تأخیر TCP",
                    style = MaterialTheme.typography.titleMedium,
                )

                nodes.forEach { node ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    node.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                )

                                Text(
                                    "${node.protocol} • ${node.host}:${node.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            Text(
                                node.latencyMs?.let { "$it ms" } ?: "بدون پاسخ",
                                color = if (node.latencyMs == null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        Button(
                            onClick = {
                                try {
                                    onOpenNewProfile(
                                        NewProfileArgs(
                                            importName = node.name,
                                            localConfigJson =
                                                FreeRouteCatalog.buildSingBoxConfig(
                                                    listOf(node)
                                                ),
                                        )
                                    )
                                } catch (error: Exception) {
                                    status =
                                        "ساخت پروفایل ناموفق بود: ${error.message ?: "نامشخص"}"
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("افزودن سرور به پروفایل‌ها")
                        }
                    }
                }
            }

            Text(
                "افزودن Subscription",
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                "در این نسخه، اشتراک یک‌بار دریافت و به پروفایل محلی تبدیل می‌شود؛ به‌روزرسانی خودکار اشتراک هنوز پیاده‌سازی نشده است.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = subscriptionUrl,
                onValueChange = { subscriptionUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("لینک اشتراک HTTP یا HTTPS") },
                singleLine = true,
            )

            Button(
                onClick = {
                    scope.launch {
                        loading = true
                        status = "در حال دریافت اشتراک و ساخت پروفایل محلی..."

                        try {
                            val imported =
                                FreeRouteCatalog.discoverFromSubscription(
                                    subscriptionUrl.trim()
                                ).take(120)

                            val checked = FreeRouteCatalog.testAll(imported)
                            val usable = checked.filter {
                                it.latencyMs != null
                            }

                            val selected =
                                if (usable.isNotEmpty()) usable else checked

                            require(selected.isNotEmpty()) {
                                "در این اشتراک لینک پشتیبانی‌شده‌ای پیدا نشد."
                            }

                            onOpenNewProfile(
                                NewProfileArgs(
                                    importName =
                                        "FreeRoute Subscription (${selected.size})",
                                    localConfigJson =
                                        FreeRouteCatalog.buildSingBoxConfig(selected),
                                )
                            )
                        } catch (error: Exception) {
                            status =
                                "خطا در واردکردن اشتراک: ${error.message ?: "نامشخص"}"
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading && (
                    subscriptionUrl.trim().startsWith("https://") ||
                        subscriptionUrl.trim().startsWith("http://")
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("دریافت و افزودن سرورهای اشتراک")
            }

            Text(
                "ورود دستی لینک کانفیگ",
                style = MaterialTheme.typography.titleMedium,
            )

            OutlinedTextField(
                value = manualLink,
                onValueChange = { manualLink = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("VLESS / VMess / Trojan / Shadowsocks / ...")
                },
                minLines = 2,
            )

            Button(
                onClick = {
                    try {
                        val node =
                            FreeRouteCatalog.parseLink(manualLink.trim())
                                ?: throw IllegalArgumentException(
                                    "فرمت لینک پشتیبانی نمی‌شود."
                                )

                        onOpenNewProfile(
                            NewProfileArgs(
                                importName = node.name,
                                localConfigJson =
                                    FreeRouteCatalog.buildSingBoxConfig(
                                        listOf(node)
                                    ),
                            )
                        )
                    } catch (error: Exception) {
                        status =
                            "خطا در ورود لینک: ${error.message ?: "نامشخص"}"
                    }
                },
                enabled = listOf(
                    "vless://",
                    "vmess://",
                    "trojan://",
                    "ss://",
                    "shadowsocks://",
                    "hysteria2://",
                    "hy2://",
                    "tuic://",
                ).any {
                    manualLink.trim().startsWith(it, ignoreCase = true)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("ورود کانفیگ")
            }

            Text(
                "توجه: عدد ms زمان اتصال TCP به سرور است، نه سرعت دانلود. سرورهای عمومی ممکن است ناامن، شلوغ یا ازکارافتاده باشند.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
