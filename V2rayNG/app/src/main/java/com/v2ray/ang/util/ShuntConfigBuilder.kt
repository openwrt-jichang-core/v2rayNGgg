package com.v2ray.ang.util

import android.text.TextUtils
import android.util.Log
import com.tencent.mmkv.MMKV
import com.v2ray.ang.handler.MmkvManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.HashSet
import java.util.ArrayList

object ShuntConfigBuilder {

    private const val TAG = "ShuntConfig"

    // 策略定义：保留这些分流规则
    private val strategies = mapOf(
        "netflix" to listOf("geosite:netflix", "domain:netflix.com", "domain:nflxvideo.net", "domain:nflxext.com"),
        "youtube" to listOf("geosite:youtube", "domain:youtube.com", "domain:googlevideo.com", "domain:ytimg.com"),
        "google"  to listOf("geosite:google", "domain:google.com", "domain:googleapis.com", "domain:gstatic.com"),
        "openai"  to listOf("geosite:openai", "domain:openai.com", "domain:chatgpt.com", "domain:ai.com")
    )

    fun build(mainGuid: String, configFetcher: (String) -> String?): String? {
        Log.e(TAG, "========== 开始生成配置 (去自动选择版) ==========")
        try {
            val mainJsonStr = configFetcher(mainGuid) ?: return null
            val fullJson = JSONObject(mainJsonStr)

            val outbounds = fullJson.getJSONArray("outbounds")
            outbounds.getJSONObject(0).put("tag", "proxy")

            val routing = fullJson.optJSONObject("routing") ?: JSONObject()
            val originalRules = routing.optJSONArray("rules") ?: JSONArray()
            // 移除可能存在的 balancers 定义，因为我们去掉了自动选择
            val balancers = JSONArray()

            val mmkv = MMKV.defaultMMKV()
            val addedNodeGuids = HashSet<String>()
            addedNodeGuids.add(mainGuid)

            // 1. 强制开启嗅探 (保留优化)
            var inbounds = fullJson.optJSONArray("inbounds")
            if (inbounds == null || inbounds.length() == 0) {
                inbounds = JSONArray()
                val socks = JSONObject()
                socks.put("tag", "socks")
                socks.put("port", 10808)
                socks.put("protocol", "socks")
                socks.put("listen", "127.0.0.1")
                socks.put("settings", JSONObject().put("auth", "noauth").put("udp", true))
                inbounds.put(socks)
            }
            for (i in 0 until inbounds.length()) {
                val inbound = inbounds.getJSONObject(i)
                val sniffing = JSONObject()
                sniffing.put("enabled", true)
                sniffing.put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                sniffing.put("routeOnly", true)
                inbound.put("sniffing", sniffing)
            }
            fullJson.put("inbounds", inbounds)

            // 2. 生成新规则
            val newRulesList = ArrayList<JSONObject>()

            strategies.forEach { (key, domains) ->
                val selectedGuid = mmkv?.decodeString("strategy_$key")
                val domainArray = JSONArray()
                domains.forEach { domainArray.put(it) }

                // --- 修改点：移除了 AUTO_SELECT_GUID 的判断 ---

                if (!TextUtils.isEmpty(selectedGuid) && selectedGuid != "default" && selectedGuid != mainGuid) {
                    // === 指定了特定节点 ===
                    val targetGuid = selectedGuid!!
                    // 检查节点配置是否存在
                    val nodeConfig = configFetcher(targetGuid)
                    if (nodeConfig != null) {
                        val targetTag = "node_$targetGuid"

                        // 将该节点添加到 outbounds
                        addNodeToOutbounds(targetGuid, targetTag, nodeConfig, outbounds, addedNodeGuids)

                        val rule = JSONObject()
                        rule.put("type", "field")
                        rule.put("outboundTag", targetTag)
                        rule.put("domain", domainArray)
                        newRulesList.add(rule)
                        Log.e(TAG, "策略 [$key] -> 指向节点: $targetTag")
                    } else {
                        // 节点不存在，回退到 proxy
                        Log.e(TAG, "策略 [$key] -> 节点配置丢失，回退默认")
                    }
                } else {
                    // === 默认/未选择/选了主节点 ===
                    // 不添加特殊规则，让它自然匹配后续的默认规则或走主代理
                    // 或者显式指向 proxy
                    val rule = JSONObject()
                    rule.put("type", "field")
                    rule.put("outboundTag", "proxy")
                    rule.put("domain", domainArray)
                    newRulesList.add(rule)
                    Log.e(TAG, "策略 [$key] -> 使用默认主节点")
                }
            }

            // 3. 合并规则
            val finalRules = JSONArray()
            for (rule in newRulesList) {
                finalRules.put(rule)
            }
            for (i in 0 until originalRules.length()) {
                finalRules.put(originalRules.get(i))
            }

            routing.put("rules", finalRules)
            routing.put("balancers", balancers) // 空数组
            routing.put("domainStrategy", "IPIfNonMatch")
            fullJson.put("routing", routing)

            // 移除 observatory，因为去掉了自动选择不需要测速
            fullJson.remove("observatory")

            return fullJson.toString()

        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            return null
        }
    }

    private fun addNodeToOutbounds(
        guid: String,
        tag: String,
        nodeJsonStr: String?,
        outbounds: JSONArray,
        addedGuids: HashSet<String>
    ) {
        if (addedGuids.contains(guid) || nodeJsonStr == null) return
        try {
            val nodeJson = JSONObject(nodeJsonStr)
            val nodeOutbound = nodeJson.getJSONArray("outbounds").getJSONObject(0)
            nodeOutbound.put("tag", tag)
            outbounds.put(nodeOutbound)
            addedGuids.add(guid)
        } catch (e: Exception) {
            Log.e(TAG, "添加节点失败: $guid", e)
        }
    }
}
