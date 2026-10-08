# Release 使用 R8 优化和资源压缩。
# Room 与 kotlinx.serialization 的 consumer rules 随依赖合并，不额外保留整包类。
# 这里仅在引入运行时反射等需要保留名称的调用后增加精确规则。
