# :data 里没有任何反射加载的东西，理论上不需要 keep 规则。
# 唯一需要注意的是 SQLiteOpenHelper 的子类：Android 框架会通过类名反射实例化
# ContentProvider（我们没有），而 SQLiteOpenHelper 是直接 new 出来的，不受 R8 影响。
# 保留文件是为了让模块结构统一、未来加规则时有地方放。
-keepnames class com.thermo.data.progress.** { *; }