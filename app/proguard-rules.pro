# R8 规则（release 构建）。
#
# 暂时是空的，因为还没有需要 keep 的东西：Compose、ML Kit、AndroidX 的 AAR 都自带
# consumer rules，而本项目的类没有被反射调用（鸭子那一套在 assets/duck 里，不经过 R8）。
#
# 出问题时的排查顺序：logcat 里看到 ClassNotFoundException / NoSuchMethodError /
# NoClassDefFoundError，那就是少了 keep；本地二分就把 minifyEnabled 改回 false 试一次。
# 真要加 keep 的时候，**连原因一起写在这里** —— 不写原因的 keep 规则没人敢删。
