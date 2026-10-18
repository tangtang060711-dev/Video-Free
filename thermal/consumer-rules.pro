# 本模块没有反射入口，无需 keep。
# 反射发生在运行时通过 Class.forName 访问 android.os.ThermalService（系统类），
# 系统类不受 R8 影响。
