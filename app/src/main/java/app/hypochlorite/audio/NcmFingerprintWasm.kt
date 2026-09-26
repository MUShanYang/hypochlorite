package app.hypochlorite.audio

import app.hypochlorite.audio.wasm.AfpQueryModule
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Machine
import com.dylibso.chicory.runtime.Store
import com.dylibso.chicory.runtime.WasmFunctionHandle
import com.dylibso.chicory.wasm.types.ExternalType
import com.dylibso.chicory.wasm.types.FunctionImport
import com.dylibso.chicory.wasm.types.FunctionType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 网易听歌识曲的指纹提取：构建期把上游 wasm 编成 JVM 字节码（Chicory AOT），运行时直接调。
 *
 * 关键结论（Node 侧实测）：**不用复刻 embind**。那 27 个 import 里只有 `a.r`（memcpy）
 * 会参与算法本身，其余全是类型注册的簿记调用，空实现就能跑出与官方逐字节相同的指纹。
 * 于是宿主只需要：跑 C++ 静态初始化 → 从注册回调里认出识曲函数 → 按 wasm 调用约定喂进去。
 *
 * 调用约定抄自官方 glue 生成的 wrapper 源码（`ExtractQueryFP` 的入参类型是 `std::string`）：
 * - 入参 = `malloc(4 + n*4 + 1)`，前 4 字节是小端长度，后面直接是 Float32 PCM 的原始字节；
 * - 出参 = 返回地址处放着的 libc++ `std::vector<int8_t>{begin, end, cap}`。
 *
 * 导出名被上游 mangle 成了单字母（`C`=静态初始化、`E`=malloc），识曲函数不是导出而是表项，
 * 所以它的坐标从 `__embind_register_function` 的参数里现取，不写死在代码里。
 *
 * AOT 由 `app` 模块的 `compileFingerprintWasmAot` 任务在构建期生成 [AfpQueryModule]；
 * 解释器路径已去掉。JVM 实测 warm 指纹从约 2.3s 降到约 70–80ms，golden 字节不变。
 */
class NcmFingerprintWasm : AudioFingerprintGenerator {

    private val mutex = Mutex()

    /** AOT 实例建起来不便宜，建一次复用到进程结束。 */
    private var host: Host? = null

    /**
     * 提取指纹。[pcmMono8k] 必须是 8kHz 单声道、[-1,1] 归一的 Float32（见 [AUDIO_MATCH_SAMPLE_RATE]）。
     *
     * 锁同时护住「只建一个实例」和「wasm 内部全局状态不被并发踩到」。
     */
    override suspend fun generate(pcmMono8k: FloatArray): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val h = host ?: Host().also { host = it }
            h.fingerprint(pcmMono8k)
        }
    }

    /** 进识别页时预热：把 AOT Machine / 静态初始化先跑完，第一次点按少等一段冷启动。 */
    override suspend fun warmUp() {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (host == null) host = Host()
            }
        }
    }

    private class Host {
        private val module = AfpQueryModule.load()

        /** 识曲函数在 wasm 表里的两个槽位，由静态初始化期间的注册回调填。 */
        private var invokeSlot = -1
        private var targetSlot = -1

        private val instance: Instance
        private val machine: Machine
        private val mallocFunc: Int
        private var invokeFunc = -1

        init {
            val store = Store()
            for (i in 0 until module.importSection().importCount()) {
                val imp = module.importSection().getImport(i) as? FunctionImport ?: continue
                val type = module.typeSection().getType(imp.typeIndex())
                store.addFunction(HostFunction(imp.module(), imp.name(), type, handlerFor(imp.name(), type)))
            }
            instance = store.instantiate("afp") { imports ->
                Instance.builder(AfpQueryModule.load())
                    .withMachineFactory(AfpQueryModule::create)
                    .withImportValues(imports)
                    .withStart(false)
                    .build()
            }
            machine = instance.getMachine()
            machine.call(exportedFunc("C"), longArrayOf())
            mallocFunc = exportedFunc("E")
            if (invokeSlot < 0) error("afp.query.wasm 没有注册 $QUERY_FUNCTION，指纹资源与代码不匹配")
            invokeFunc = instance.table(0).ref(invokeSlot)
        }

        /**
         * 宿主侧实现。内存一律从回调自带的 [Instance] 取，因为 import 有可能在
         * `instance` 这个字段还没赋值完的时候就被跑到。
         */
        private fun handlerFor(name: String, type: FunctionType): WasmFunctionHandle {
            val results = type.returns().size
            return when (name) {
                // 算法唯一真正用到的宿主函数：线性内存内的复制。先整块读出再写回，允许区间重叠。
                MEMCPY_IMPORT -> WasmFunctionHandle { inst, args ->
                    val mem = inst.memory()
                    val dst = args[0].toInt()
                    val src = args[1].toInt()
                    val len = args[2].toInt()
                    mem.write(dst, mem.readBytes(src, len))
                    longArrayOf(args[0])
                }
                // __embind_register_function(name, argCount, argTypes, sig, invokerTableSlot, targetTableSlot)
                REGISTER_FUNCTION_IMPORT -> WasmFunctionHandle { inst, args ->
                    if (inst.memory().readCString(args[0].toInt()) == QUERY_FUNCTION) {
                        invokeSlot = args[4].toInt()
                        targetSlot = args[5].toInt()
                    }
                    longArrayOf()
                }
                // 剩下的都是类型注册簿记：wasm 不读它们的返回值，按签名给全 0 即可。
                else -> WasmFunctionHandle { _, _ -> LongArray(results) }
            }
        }

        fun fingerprint(pcm: FloatArray): String {
            require(pcm.isNotEmpty()) { "空音频算不出指纹" }
            val mem = instance.memory()
            val payload = pcm.size * 4
            val strPtr = machine.call(mallocFunc, longArrayOf((payload + 5).toLong()))[0].toInt()
            if (strPtr == 0) error("wasm 侧分配不出 ${payload + 5} 字节的音频缓冲")
            mem.writeI32(strPtr, payload)
            val raw = ByteBuffer.allocate(payload).order(ByteOrder.LITTLE_ENDIAN)
            for (v in pcm) raw.putFloat(v)
            mem.write(strPtr + 4, raw.array())
            val rv = machine.call(invokeFunc, longArrayOf(targetSlot.toLong(), strPtr.toLong()))[0].toInt()
            val begin = mem.readInt(rv)
            val end = mem.readInt(rv + 4)
            if (begin <= 0 || end <= begin || end - begin > MAX_FINGERPRINT_BYTES) {
                error("指纹提取返回了非法的 vector 区间 [$begin, $end)")
            }
            return Base64.getEncoder().encodeToString(mem.readBytes(begin, end - begin))
        }

        private fun exportedFunc(name: String): Int = module.exportSection().let { sec ->
            (0 until sec.exportCount())
                .map { sec.getExport(it) }
                .firstOrNull { it.name() == name && it.exportType() == ExternalType.FUNCTION }
                ?.index()
                ?: error("afp.query.wasm 缺少导出 $name")
        }

        private companion object {
            const val MEMCPY_IMPORT = "r"
            const val REGISTER_FUNCTION_IMPORT = "l"
            const val QUERY_FUNCTION = "ExtractQueryFP"

            /** 指纹长度随音频内容变（实测 3 秒：纯正弦 288B，真歌 738~786B），这里只挡越界读。 */
            const val MAX_FINGERPRINT_BYTES = 64 * 1024
        }
    }

    companion object {
        /** 构建期是否成功编出了可用的 AOT 模块（缺 wasm 时是 stub，load 会抛）。 */
        fun isAvailable(): Boolean = runCatching { AfpQueryModule.load(); true }.getOrDefault(false)
    }
}
