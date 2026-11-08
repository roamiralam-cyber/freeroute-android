
package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.navigation.NewProfileArgs
import io.nekohasekai.sfa.freeroute.FreeRouteCatalog
import io.nekohasekai.sfa.freeroute.FreeRouteNode
import kotlinx.coroutines.launch

@Composable
fun FreeRoutePanel(
    onOpenNewProfile: (NewProfileArgs) -> Unit
) {
    var subscription by remember { mutableStateOf("") }
    var manualLink by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf<List<FreeRouteNode>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "FreeRoute",
                style = MaterialTheme.typography.titleLarge
            )

            Text("یافتن سرورهای رایگان")

            Button(
                onClick = {
                    scope.launch {
                        loading = true
                        message = ""
                        try {
                            val found = FreeRouteCatalog.discover()
                            nodes = FreeRouteCatalog.testAll(found)
                            message = "تعداد سرورهای پیدا شده: ${nodes.size}"
                        } catch (e: Exception) {
                            message = "خطا در دریافت سرورها: ${e.message}"
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) {
                    CircularProgressIndicator()
                } else {
                    Text("جست‌وجوی سرورها و بررسی تأخیر")
                }
            }

            if (message.isNotBlank()) {
                Text(message, style = MaterialTheme.typography.bodySmall)
            }

            items(nodes, key = { it.id }) { node ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(node.name)
                        Text(
                            "${node.protocol} • ${node.host}:${node.port}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        node.latencyMs?.let { "$it ms" } ?: "ناموفق",
                        color = if (node.latencyMs == null)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }
                Button(
                    onClick = {
                        onOpenNewProfile(
                            NewProfileArgs(
                                importName = node.name,
                                importUrl = node.link
                            )
                        )
                    }
                ) {
                    Text("افزودن")
                }
            }

            Text("افزودن اشتراک")

            OutlinedTextField(
                value = subscription,
                onValueChange = { subscription = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("لینک Subscription") },
                singleLine = true
            )

            Button(
                onClick = {
                    if (subscription.isNotBlank()) {
                        onOpenNewProfile(
                            NewProfileArgs(
                                importName = "FreeRoute Subscription",
                                importUrl = subscription.trim()
                            )
                        )
                    }
                },
                enabled = subscription.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("افزودن اشتراک")
            }

            Text("ورود دستی کانفیگ")

            OutlinedTextField(
                value = manualLink,
                onValueChange = { manualLink = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("لینک VLESS / VMess / Trojan / ...") }
            )

            Button(
                onClick = {
                    if (manualLink.isNotBlank()) {
                        onOpenNewProfile(
                            NewProfileArgs(
                                importName = "Manual configuration",
                                importUrl = manualLink.trim()
                            )
                        )
                    }
                },
                enabled = manualLink.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("واردکردن کانفیگ")
            }
        }
    }
}
