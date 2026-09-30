# 验证实际黏度与 OpenCL 内核资源

`native/tools/diagnose_lbm_opencl.py` 用 Python 标准库通过 OpenCL C API 调用当前
`aero_lbm_jni.cpp` 中的内核，不需要构建 JNI DLL，不依赖 NumPy/PyOpenCL。
这是隔离数值更新与编译行为的实验，不包含 Java、动态几何、温度输运或 CPU 回读性能。

本环境是 WSL2，OpenCL 只发现 PoCL CPU。脚本会在 WSL 中、加载 OpenCL 之前拒绝执行
GPU 实验，也不会选择 CPU 设备。在这里仅使用 `--extract-only`。
实际实验在 **Windows 原生 PowerShell + Windows Python** 中运行，工作目录为 `fabric-mod`。
如果项目还在 WSL 文件系统，可以从 Windows 访问该目录或把项目中的 `native/tools` 和
`native/src/aero_lbm_jni.cpp` 按相同相对布局复制到 Windows。

每次选择新的输出目录，避免覆盖实验记录。驱动编译仍可能耗时和占用主机内存；小网格
限制的是场数组大小，并不限制驱动编译器内存。脚本仅保留所选计算内核、初始化和输出
内核的入口，保留全部辅助函数，减少无关编译工作；没有改变所选内核的数学表达式。

## 1. 用剪切波衰减测量黏度

初始化完全周期的空域：

```text
rho = 1
ux(y, 0) = A sin(2 pi m y / Ny)
uy = uz = 0
```

默认 `A=0.01, m=1`，网格为 `4 x 32 x 4`（512 格点）。主机传给内核的
`tau = 0.5 + 3 nu`，关闭 SGS、风扇、浮力、海绵层、nudge，温度为零。
三个方向均周期，初始速度沿 x、变化沿 y，因此连续方程中的自对流项为零。

低马赫、线性剪切模式应满足：

```text
k = 2 pi m / Ny
A(t) = A(0) exp(-nu k^2 t)
nu_measured = -slope(log A(t)) / k^2
```

脚本用正弦和余弦投影提取振幅，排除初始瞬态后做最小二乘拟合。也计算后半段拟合，
帮助发现瞬态未衰减或曲线不再呈指数衰减。`mean_rho_proxy` 来自生产输出内核，其密度
经过截断，不能替代对原始分布的严格质量守恒检查。

```powershell
py native/tools/diagnose_lbm_opencl.py viscosity --kernel forced --out native/diagnostics/shear-y32
```

默认扫描 `0.02, 0.01, 0.004, 0.001, 0.0001`，运行 1024 步，每 32 步采样，排除前 128 步。
输出：

- `viscosity.csv`：设定黏度、FP32 tau、实测黏度、拟合 R²、后半段拟合结果。
- `decay_XX.csv`：每次采样的振幅、平均密度代理量、横向速度。
- `metadata.json`：源码哈希、设备/驱动、参数和结果。
- `kernel.cl`、`build.log`、`program.ptx` 或 `program.bin`：实际编译输入和产物。

当前源码在二阶松弛中把速率上限截断为 1.95，因此理论预测为：

| 设定 nu | 预期的长波极限测量值 |
| --- | --- |
| 0.02 | 约 0.02 |
| 0.01 | 约 0.01 |
| 0.004 | 约 0.0042735 |
| 0.001 | 约 0.0042735 |
| 0.0001 | 约 0.0042735 |

这里的数值是**实验前预测**，不是已经测出的结果。有限波长误差、FP32 舍入和瞬态可能
产生偏差，不能只看某次输出小数点后的位数。若低黏度几组的平台重复出现，并接近
`(1/1.95 - 0.5)/3`，就支持“截断造成黏度下限”的判断。

用更长波长和更长时间复核，保持衰减量大致相当：

```powershell
py native/tools/diagnose_lbm_opencl.py viscosity --kernel forced --ny 64 --steps 4096 --sample-every 128 --discard 512 --out native/diagnostics/shear-y64
```

必要时把振幅降为 `--amplitude 0.005` 检查线性区；或加 `--strict-math` 检查 fast-math
对结果的影响。`--kernel benchmark` / `--kernel tgv` 可验证另外两份碰撞实现。
脚本直接指定 tau，故它验证内核对输入 tau 的响应；主机端 Re/单位换算仍需独立验证。

判读时至少同时看：不同波长是否趋向一致、前后半段拟合是否一致、振幅是否确有可测的
衰减、R² 是否接近 1、是否产生明显横向流动。近乎不衰减时，不应凭 R² 单独确认黏度。

## 2. 检查实际编译产物，再测 GPU 事件时间

先只编译，**不分配网格场数组、不执行内核**：

```powershell
py native/tools/diagnose_lbm_opencl.py inspect --kernel forced --out native/diagnostics/inspect-forced
```

构建使用生产配置 `-cl-fast-relaxed-math`；NVIDIA 上尝试增加 `-cl-nv-verbose`。
若驱动明确返回“不支持构建选项”，仅移除此诊断选项后重试。
日志可能因驱动版本/缓存而没有资源统计；空日志不代表没有 spill。

查看对应 `stream_collide_hydro_forced_step` 的：

- 编译日志中的实际寄存器使用量、stack frame、spill stores/loads（若驱动提供）。
- `metadata.json` 中的最大 work-group、推荐倍数、local/private memory 查询结果。
- 导出的二进制；若驱动返回可识别 PTX，保存在 `program.ptx`，否则保留原始 `.bin`。

`CL_KERNEL_PRIVATE_MEM_SIZE` 是实现报告的私有内存量，**不等于实测 spill 流量**。
PTX 的 `.reg` 声明是虚拟寄存器，不能当作物理寄存器数；PTX 中的 local load/store
也是线索，最终寄存器分配还可能产生额外 spill。寄存器很多不自动意味着“越少越快”。
不要直接给 OpenCL 程序套用面向 CUDA 的 `ncu` 工作流并假定能捕获内核。

资源信息获取成功后，从小域开始记录事件时间：

```powershell
py native/tools/diagnose_lbm_opencl.py inspect --kernel forced --time-kernel --grid 32 --nu 0.001 --out native/diagnostics/time-g32
py native/tools/diagnose_lbm_opencl.py inspect --kernel forced --time-kernel --grid 64 --nu 0.001 --out native/diagnostics/time-g64
```

GPU 事件 START/END 只围住所选水动力内核，预热 5 次、测量 10 次；不把初始化、编译、
上传或输出内核计入。每次测量后等待完成，保证事件有效。默认 local size 由驱动决定，
与当前生产代码一致。可以使用 `--local-size 64/128/256` 比较，但必须不超过内核最大值。
小域用于确认流程，大域才适合评价吞吐；`128³` 是显式选择，脚本不会自动增长。

仅凭资源报告还不能确认“主因”。需要在同一 GPU、驱动、网格、flags、数学选项、
work-group 下做保留数值语义的 A/B：

1. 基线：当前完整矩变换。
2. 变体：仅计算被消费的六个碰撞前二阶中心矩。
3. 再一个变体：对逆变换做轴向分解/常量化，缩短中间数组的存活时间。

每次同时记录剪切衰减与 GPU 时间。数值检查通过且时间下降，才能确认该变体的收益；
再结合实际指令和资源变化判断原因，不能要求原本为零的 spill 继续减少。
这些优化变体没有包含在当前脚本中，避免把“验证”与“修改求解器”混在一起。

## 用户回传的基线结果

以下数据来自用户在 NVIDIA GeForce RTX 3050 Ti Laptop GPU 上运行诊断脚本后回传的
日志，本地 WSL 没有执行 GPU 实验。所选入口是 `stream_collide_hydro_forced_step`，
编译选项为 `-cl-fast-relaxed-math -cl-nv-verbose`。完整命令、源码哈希和驱动版本应以
对应输出目录中的 `metadata.json` 为准，本次粘贴的日志没有包含这些信息。

| 请求 nu | 实测 nu | 后半段拟合 nu |
| --- | --- | --- |
| 0.02 | 0.0200504651 | 0.0200514530 |
| 0.01 | 0.0100282189 | 0.0100285235 |
| 0.004 | 0.0042863754 | 0.0042876288 |
| 0.001 | 0.0042863754 | 0.0042876288 |
| 0.0001 | 0.0042863754 | 0.0042876288 |

各组 R² 均超过 0.99999996。三组低黏度输入得到完全相同的拟合结果，平台比
`(1/1.95 - 0.5)/3` 高约 0.30%；两组未触发截断的结果也比输入高约 0.25–0.28%。
这支持所选内核在该剪切波实验中存在由 1.95 截断造成的黏度下限；小偏差的具体来源
尚未分离，也不能据此认定解除截断后仍能稳定求解低黏度流动。

编译器报告主内核使用 72 个寄存器、328 B 栈帧、0 B spill stores、0 B spill loads。
OpenCL 查询报告 private memory 为 328 B。**当前日志没有报告寄存器 spill**，但栈帧
依然可能承载直接放入线程私有内存的数组。不能把栈帧大小解释为每步实际访存量，
也不能仅凭这些资源数字认定性能瓶颈；需要结合 PTX/最终机器码和 A/B 时间。

| 网格 | 测量次数 | 中位 GPU 时间 | 中位 MLUPS | 分布函数理论读写量 / 时间 |
| --- | --- | --- | --- | --- |
| 32³ | 10 | 0.218624 ms | 149.8829 | 32.3747 GB/s |
| 64³ | 100 | 1.536 ms | 170.6667 | 36.864 GB/s |

最后一列只按每格 `27 × 4 × 2 = 216 B` 计算，不是硬件计数器测得的 DRAM 带宽，
不包括 payload、线程私有数组等访问，也没有扣除缓存命中。64³ 比 32³ 的吞吐高约 14%；
这组隔离配置尚未复现此前约 40 MLUPS 的结果，仍需核对原始测试配置才能解释差异。

## 在 WSL 内可以做的静态操作

```bash
python3 native/tools/diagnose_lbm_opencl.py viscosity --extract-only --out /tmp/lbm-shear-source
```

此模式只提取文本、计算哈希，不加载 OpenCL，也不调用 GPU/PoCL。

参考：

- [Khronos: clGetKernelWorkGroupInfo](https://registry.khronos.org/OpenCL/specs/unified/refpages/man/html/clGetKernelWorkGroupInfo.html)
- [NVIDIA OpenCL Programming Guide：编译器资源报告](https://developer.download.nvidia.com/compute/DevZone/docs/html/OpenCL/doc/OpenCL_Programming_Guide.pdf)
