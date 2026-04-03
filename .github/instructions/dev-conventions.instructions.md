---
applyTo: "src/**"
description: "Use when modifying or adding code in auto-manim. Covers backward compatibility policy and naming symmetry conventions for the testing phase."
---
# Auto-Manim 开发约定（测试阶段）

## 无需向后兼容

项目当前处于测试阶段，尚未正式发布。修改接口、字段、方法签名等无需考虑向后兼容性，直接修改源代码即可。不需要：
- 添加 `@Deprecated` 注解保留旧接口
- 编写适配层或兼容代码
- 保留旧的字段/方法名

## 命名对称原则

新增与已有逻辑平行的功能时，必须重命名原有命名使其与新命名保持对称，而非仅为新功能选择带前缀/后缀的名称。

示例：已有字段 `code`，新增 GeoGebra 代码字段时：
- 正确：将 `code` 重命名为 `manimCode`，新增 `geogebraCode`
- 错误：保留 `code` 不变，新增 `geogebraCode`（不对称）

操作步骤：
1. 将原命名加上具体含义前缀/后缀（如 `code` → `manimCode`）
2. 全局替换所有引用处
3. 新增对称命名的新字段/方法（如 `geogebraCode`）
