![](docs/bmd-logo.png)

[![GitHub license](https://img.shields.io/badge/license-Apache%202-blue)](https://github.com/bytedance/ByteX/blob/master/LICENSE)


**BoostMultiDex**是一个用于Android低版本设备（4.X及以下，SDK < 21）快速加载多DEX的解决方案，由抖音/Tiktok Android技术团队出品。

相比于Android官方原始MultiDex方案，它能够减少80%以上的黑屏等待时间，挽救低版本Android用户的升级安装体验。并且，不同于目前业界所有优化方案，BoostMultiDex方案是从Android Dalvik虚拟机底层机制入手，从根本上解决了安装APK后首次执行MultiDex耗时过长问题。

## 背景

我们知道，Android低版本（4.X及以下，SDK < 21）的设备，采用的Java运行环境是Dalvik虚拟机。它相比于高版本，最大的问题就是在安装或者升级更新之后，首次冷启动的耗时漫长。这常常需要花费几十秒甚至几分钟，用户不得不面对一片黑屏，熬过这段时间才能正常使用APP。

这是非常影响用户的使用体验的。尤其在海外，像东南亚以及拉美等地区，还存有着很大量的低端机。4.X以下低版本用户虽然比较少，但对于抖音及Tiktok这样有着亿级规模的用户的APP，即使占比10%，数目也有上千万。因此如果想要打通下沉市场，这部分用户的使用和升级体验是绝对无法忽视的。

这个问题的根本原因就在于，安装或者升级后首次MultiDex花费的时间过于漫长。为了解决这个问题，我们挖掘了Dalvik虚拟机的底层系统机制，对DEX相关处理逻辑进行了重新设计，最终推出了BoostMultiDex方案，挽救低版本Android用户的升级安装体验。

## 技术要点

BoostMultiDex方案的技术实现要点如下：

1. 利用系统隐藏函数，直接加载原始DEX字节码，避免ODEX耗时
2. 多级加载，在DEX字节码、DEX文件、ODEX文件中选取最合适的产物启动APP
3. 单独进程做OPT，并实现合理的中断及恢复机制

更重要的是，BoostMultiDex已经在抖音/TikTok亿级全球用户上验证通过，可以说涵盖了各个国家、各种复杂情况的Android机型，目前业界其他大型APP都很难涉及到如此广泛的规模。由此，我们也解决了各种奇怪的兼容性问题，最大程度上确保了技术方案的稳定性。

## 快速接入

build.gradle的dependencies中添加依赖：

```gradle
dependencies {
... ...
    implementation 'com.bytedance.boost_multidex:boost_multidex:${ARTIFACT_VERSION}'
}
```

与官方MultiDex类似，在Application.attachBaseContext的最前面进行初始化即可：

```java
public class YourApplication extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        
        BoostMultiDex.install(base);
        
... ...
    }
```

## 编译构建

如果想自行编译打包，需要使用[R16B版本的NDK](https://developer.android.com/ndk/downloads/older_releases)以支持armeabi架构，如果不需要，可以直接在boost_multidex/build.gradle中去掉此依赖。

执行以下命令即可构建本地aar包：

```gralde
./gradlew :boost_multidex:assembleRelease
```

产物为`boost_multidex/build/outputs/aar/boost_multidex-release.aar`

## 性能对比

| Android版本 | 厂商 | 机型 | 原始MultiDex耗时(s) | BoostMultiDex耗时(s) |
| :------: | :------: | :------: | :------: | :------: |
| 4.4.2 | LG | LGMS323 | 33.545 | 5.014 |
| 4.4.4 | MOTO | G | 45.691 | 6.719 |
| 4.3 | Samsung | GT-N7100 | 24.186 | 3.660 |
| 4.3.0 | Samsung | SGH-T999 | 30.331 | 3.791 |
| 4.2.2	 | HUAWEI | Hol-T00 | 崩溃 | 3.724 |
| 4.2.1 | HUAWEI | G610-U00 | 36.465 | 4.981 |
| 4.1.2	 | Samsung | I9100	 | 30.962 | 5.345 |

以上是在抖音上测得的实际数据，APK中共有6个Secondary DEX，显而易见，BoostMultiDex方案相比官方MultiDex方案，其耗时有着本质上的优化，基本都只到原先的11%~17%之间。**也就是说BoostMultiDex减少了原先过程80%以上的耗时。**另外我们看到，其中有一个机型，在官方MultiDex下是直接崩溃，无法启动的。使用BoostMultiDex也将使得这些机型可以焕发新生。

## 详细原理

[抖音BoostMultiDex优化实践：Android低版本上APP首次启动时间减少80%（一）](https://mp.weixin.qq.com/s/jINCbTJ5qMaD6NdeGBHEwQ)

[抖音BoostMultiDex优化实践：Android低版本上APP首次启动时间减少80%（二）](https://mp.weixin.qq.com/s/ILDTykAwR0xIkW-d1YzRHw)