package me.xjqsh.lrtactical.client.audio;

import com.tacz.guns.api.client.animation.AnimationController;
import com.tacz.guns.api.client.animation.AnimationSoundChannelContent;
import com.tacz.guns.api.client.animation.ObjectAnimation;
import com.tacz.guns.api.client.animation.ObjectAnimationSoundChannel;
import com.tacz.guns.api.client.animation.statemachine.LuaAnimationStateMachine;
import com.tacz.guns.client.sound.GunSoundInstance;
import me.xjqsh.lrtactical.client.renderer.item.MeleeItemRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.*;

/**
 * 修复检视音效的两个问题：
 * 1. 重复按检视键时音效叠加重复播放
 * 2. 切换到其他物品/空手后检视音效不停止
 * <p>
 * 原理：追踪动画状态机产生的 {@link GunSoundInstance}，
 * 在相同 ResourceLocation 的新音效触发时停止旧音效；
 * 在手动物品变化时停止所有关联音效。
 * <p>
 * <b>关于 TaCZ 1.1.8 的兼容性：</b>
 * TaCZ 1.1.8 把 {@code GunSoundInstance} 的父类从
 * {@code EntityBoundSoundInstance} 改成了 {@code AbstractSoundInstance}。
 * 本类旧实现会去读 {@code EntityBoundSoundInstance.f_119675_} 这个 private final 字段，
 * 在 1.1.8 下接收者已不是该类的子类，{@code Field.get} 会抛出
 * {@link IllegalArgumentException}（RuntimeException），从 ClientTickEvent 逃逸后直接崩游戏。
 * <p>
 * 现在改为使用 TaCZ 自己暴露的追踪信息与 Minecraft 公开的坐标 API：
 * <ul>
 *   <li>{@code EntityTrackingGunSoundInstance} 是 TaCZ 1.1.8 用来跟随实体的音效类型，
 *       非本地玩家的音效才会用它；它用 {@link WeakReference} 持有实体。</li>
 *   <li>本地玩家的动画音效（TaCZ 传 {@code trackEntity = false} 调用
 *       {@code SoundPlayManager.playAnimationSound}）不跟随实体，
 *       其坐标就是本地玩家播放时的位置，用公开的 {@code getX/getY/getZ} 判断归属。</li>
 * </ul>
 * 所有反射访问都做了 null 守卫 + {@link RuntimeException} 兜底，任何失败都只降级为
 * "音效修复不生效"，绝不会再让异常逃逸到 tick 事件里。
 *
 * @author Codex (originally in fixed JAR, decompiled and restored)
 */
@OnlyIn(Dist.CLIENT)
public final class AnimationSoundStopper {

    private static final Logger LOGGER = LogManager.getLogger("lrtactical");

    private static final String ENTITY_TRACKING_SOUND_CLASS = "com.tacz.guns.client.sound.EntityTrackingGunSoundInstance";
    /** 本地动画音效的坐标偏移容差（格）。本地音效坐标固定为播放瞬间的玩家位置。 */
    private static final double LOCAL_SOUND_RADIUS_SQR = 64.0D;

    private static final Field ANIMATION_PROTOTYPES;
    private static final Field SOUND_ENGINE;
    private static final Field PLAYING_SOUNDS;
    /** TaCZ 1.1.8+：跟随实体的音效类型，用来识别"不是本地玩家的音效"。不存在时为 null。 */
    @Nullable
    private static final Class<?> ENTITY_TRACKING_SOUND;
    /** TaCZ 1.1.8+：{@code EntityTrackingGunSoundInstance.entityRef}，惰性解析。 */
    @Nullable
    private static Field trackedEntityRef;
    private static boolean trackedEntityRefResolved;

    @Nullable
    private static ItemStack previousStack;
    @Nullable
    private static LuaAnimationStateMachine<?> previousStateMachine;
    @Nullable
    private static LocalPlayer previousPlayer;

    /** 当前正在观察的音效集合 */
    private static final Set<GunSoundInstance> observedSounds;
    /** 每个 ResourceLocation 对应的最新音效实例 */
    private static final Map<ResourceLocation, GunSoundInstance> latestSounds;

    static {
        ANIMATION_PROTOTYPES = findDeclaredField(AnimationController.class, "prototypes");
        SOUND_ENGINE = findSrgField(SoundManager.class, "f_120349_");
        PLAYING_SOUNDS = findSrgField(SoundEngine.class, "f_120226_");

        Class<?> trackingSound = null;
        try {
            trackingSound = Class.forName(ENTITY_TRACKING_SOUND_CLASS);
        } catch (ClassNotFoundException | LinkageError e) {
            // TaCZ 1.1.7 及更早版本没有这个类，属于正常情况
            trackingSound = null;
        }
        ENTITY_TRACKING_SOUND = trackingSound;

        observedSounds = Collections.newSetFromMap(new IdentityHashMap<>());
        latestSounds = new HashMap<>();

        if (SOUND_ENGINE == null || PLAYING_SOUNDS == null) {
            LOGGER.warn("[lrtactical] 无法访问 SoundEngine 内部结构，检视音效修复已停用（不会影响游戏运行）");
        } else if (ENTITY_TRACKING_SOUND == null) {
            LOGGER.info("[lrtactical] 未找到 TaCZ 实体追踪音效类型，改用坐标匹配识别本地音效");
        }
    }

    private AnimationSoundStopper() {
    }

    /**
     * 每客户端 tick 调用。检测玩家手持物品变化以停止旧音效，
     * 并防止同一动画音效的重复播放。
     */
    public static void onClientTick(@Nullable LocalPlayer player) {
        if (player == null) {
            stopPrevious();
            clearPrevious();
            return;
        }

        ItemStack currentStack = player.getMainHandItem();
        LuaAnimationStateMachine<?> currentStateMachine = getMeleeStateMachine(currentStack);

        // 检测物品或状态机是否变化
        if (previousStateMachine != null &&
                (currentStack != previousStack || currentStateMachine != previousStateMachine)) {
            stopAnimationSounds(previousStateMachine, previousPlayer);
            clearObservedSounds();
        }

        // 处理音效重叠
        if (currentStateMachine != null) {
            stopSupersededSounds(currentStateMachine, player);
        } else {
            clearObservedSounds();
        }

        previousStack = currentStack;
        previousStateMachine = currentStateMachine;
        previousPlayer = player;
    }

    @Nullable
    private static LuaAnimationStateMachine<?> getMeleeStateMachine(ItemStack stack) {
        var renderer = IClientItemExtensions.of(stack).getCustomRenderer();
        if (renderer instanceof MeleeItemRenderer meleeRenderer) {
            return meleeRenderer.getStateMachine(stack);
        }
        return null;
    }

    /**
     * 停止所有之前的动画音效（切物品时调用）
     */
    private static void stopPrevious() {
        if (previousStateMachine != null) {
            stopAnimationSounds(previousStateMachine, previousPlayer);
        }
    }

    /**
     * 清除之前的状态追踪
     */
    private static void clearPrevious() {
        previousStack = null;
        previousStateMachine = null;
        previousPlayer = null;
        clearObservedSounds();
    }

    /**
     * 防止音效叠加：当同一个 ResourceLocation 的新音效出现时，停止旧音效。
     */
    private static void stopSupersededSounds(LuaAnimationStateMachine<?> stateMachine, LocalPlayer player) {
        if (stateMachine == null || player == null) return;

        // 收集当前状态机所有动画的音效 ResourceLocation
        Set<ResourceLocation> animationSoundIds = collectAnimationSoundIds(stateMachine);
        if (animationSoundIds.isEmpty()) {
            clearObservedSounds();
            return;
        }

        Set<GunSoundInstance> soundSet = collectLocalSounds(animationSoundIds, player);
        if (soundSet == null) return;

        // 清理 latestSounds 中不再播放的音效
        latestSounds.entrySet().removeIf(entry -> !soundSet.contains(entry.getValue()));

        // 处理每个正在播放的音效
        for (GunSoundInstance sound : soundSet) {
            if (observedSounds.contains(sound)) continue;

            ResourceLocation registryName = sound.getRegistryName();
            GunSoundInstance previous = latestSounds.put(registryName, sound);

            // 同一个 ResourceLocation 有更旧的音效 → 停止它
            if (previous != null && previous != sound && soundSet.contains(previous)) {
                previous.setStop();
            }
        }

        // 更新观察集合
        observedSounds.retainAll(soundSet);
        observedSounds.addAll(soundSet);
    }

    /**
     * 停止指定状态机产生的所有正在播放的动画音效（切物品时调用）
     */
    private static void stopAnimationSounds(LuaAnimationStateMachine<?> stateMachine, @Nullable LocalPlayer player) {
        if (stateMachine == null || player == null) return;

        Set<ResourceLocation> animationSoundIds = collectAnimationSoundIds(stateMachine);
        if (animationSoundIds.isEmpty()) return;

        Set<GunSoundInstance> soundSet = collectLocalSounds(animationSoundIds, player);
        if (soundSet == null) return;

        for (GunSoundInstance gunSound : soundSet) {
            gunSound.setStop();
        }
    }

    /**
     * 从 SoundEngine 正在播放的音效中筛出属于本地玩家、且属于该状态机的动画音效。
     *
     * @return 匹配到的音效集合；反射链路不可用时返回 null（调用方直接放弃本次处理）
     */
    @Nullable
    private static Set<GunSoundInstance> collectLocalSounds(Set<ResourceLocation> animationSoundIds, LocalPlayer player) {
        if (SOUND_ENGINE == null || PLAYING_SOUNDS == null) return null;

        Map<Object, Object> playingSounds;
        try {
            playingSounds = getPlayingSounds();
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
        if (playingSounds == null) return null;

        Set<GunSoundInstance> soundSet = Collections.newSetFromMap(new IdentityHashMap<>());
        // 复制一份 keySet，避免遍历期间被音频线程改动
        for (Object soundObj : new ArrayList<>(playingSounds.keySet())) {
            if (!(soundObj instanceof GunSoundInstance gunSound)) continue;

            if (!animationSoundIds.contains(gunSound.getRegistryName())) continue;

            if (!isLocalPlayerSound(gunSound, player)) continue;

            soundSet.add(gunSound);
        }
        return soundSet;
    }

    /**
     * 判断一个音效是否属于本地玩家。
     * <p>
     * 优先用 TaCZ 自己的追踪信息：1.1.8 起非本地玩家的音效是
     * {@code EntityTrackingGunSoundInstance}，内部用 {@link WeakReference} 持有实体；
     * 本地玩家的动画音效不走这个类型。
     * <p>
     * 兜底用 Minecraft 公开的坐标 API：本地动画音效的位置就是播放时玩家的位置。
     * 全流程不依赖 private 字段的类型假设，失败时宁可漏判也不会抛异常。
     */
    private static boolean isLocalPlayerSound(GunSoundInstance sound, LocalPlayer player) {
        // 1) TaCZ 实体追踪音效 → 跟随实体的音效（远程玩家/实体），不是本地音效
        if (ENTITY_TRACKING_SOUND != null && ENTITY_TRACKING_SOUND.isInstance(sound)) {
            return isTrackingLocalPlayer(sound, player);
        }

        // 2) 用公开坐标 API 兜底匹配本地玩家位置
        return isAtLocalPlayer(sound, player);
    }

    /**
     * TaCZ 1.1.8 的实体追踪音效：读取它自己持有的 {@code WeakReference<Entity>} 做身份比对。
     * 读取失败时保守地返回 false（不停止该音效），避免误停远程玩家的音效。
     */
    private static boolean isTrackingLocalPlayer(GunSoundInstance sound, LocalPlayer player) {
        Field field = resolveTrackedEntityRef();
        if (field == null) return false;
        try {
            Object ref = field.get(sound);
            if (!(ref instanceof WeakReference<?> weakRef)) return false;
            return weakRef.get() == player;
        } catch (IllegalAccessException | RuntimeException e) {
            // 任何反射失败都只是"这个音效不归我们管"，绝不向 tick 事件外抛异常
            return false;
        }
    }

    /**
     * 惰性解析 {@code EntityTrackingGunSoundInstance.entityRef}，只解析一次。
     */
    @Nullable
    private static Field resolveTrackedEntityRef() {
        if (!trackedEntityRefResolved) {
            trackedEntityRefResolved = true;
            if (ENTITY_TRACKING_SOUND != null) {
                trackedEntityRef = findDeclaredField(ENTITY_TRACKING_SOUND, "entityRef");
            }
        }
        return trackedEntityRef;
    }

    /**
     * 本地动画音效的位置就是播放瞬间玩家的位置（TaCZ 以 {@code trackEntity = false} 播放），
     * 因此用公开的 {@code getX/getY/getZ} 与玩家当前位置比对即可。
     */
    private static boolean isAtLocalPlayer(GunSoundInstance sound, LocalPlayer player) {
        if (sound.isRelative()) return true; // 无绝对坐标，交给上层按 ResourceLocation 去重
        double dx = sound.getX() - player.getX();
        double dy = sound.getY() - player.getY();
        double dz = sound.getZ() - player.getZ();
        return dx * dx + dy * dy + dz * dz <= LOCAL_SOUND_RADIUS_SQR;
    }

    /**
     * 从状态机的 AnimationController 收集所有动画关键帧音效的 ResourceLocation
     */
    @SuppressWarnings("unchecked")
    private static Set<ResourceLocation> collectAnimationSoundIds(LuaAnimationStateMachine<?> stateMachine) {
        Set<ResourceLocation> ids = new HashSet<>();

        Map<String, ObjectAnimation> prototypes;
        try {
            if (ANIMATION_PROTOTYPES == null) return ids;
            prototypes = (Map<String, ObjectAnimation>) ANIMATION_PROTOTYPES.get(
                    stateMachine.getAnimationController()
            );
        } catch (IllegalAccessException | RuntimeException e) {
            return ids;
        }

        if (prototypes == null) return ids;

        for (ObjectAnimation anim : prototypes.values()) {
            ObjectAnimationSoundChannel soundChannel = anim.getSoundChannel();
            AnimationSoundChannelContent content = soundChannel != null ? soundChannel.content : null;
            if (content == null) continue;

            ResourceLocation[] soundNames = content.keyframeSoundName;
            if (soundNames == null) continue;

            for (ResourceLocation soundName : soundNames) {
                if (soundName != null) {
                    ids.add(soundName);
                }
            }
        }

        return ids;
    }

    /**
     * 通过反射获取 SoundEngine 中正在播放的音效 Map
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private static Map<Object, Object> getPlayingSounds() throws IllegalAccessException {
        Minecraft mc = Minecraft.getInstance();
        SoundEngine engine = (SoundEngine) SOUND_ENGINE.get(mc.getSoundManager());
        if (engine == null) return null;
        return (Map<Object, Object>) PLAYING_SOUNDS.get(engine);
    }

    /**
     * 清除所有音效追踪状态
     */
    private static void clearObservedSounds() {
        observedSounds.clear();
        latestSounds.clear();
    }

    /**
     * 查找声明字段（用于非混淆名称，如 AnimationController.prototypes）
     */
    @Nullable
    private static Field findDeclaredField(Class<?> clazz, String name) {
        try {
            Field field = clazz.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 通过 SRG 名称查找字段（用于 Minecraft/Forge 混淆类）
     */
    @Nullable
    private static Field findSrgField(Class<?> clazz, String srgName) {
        try {
            return ObfuscationReflectionHelper.findField(clazz, srgName);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
