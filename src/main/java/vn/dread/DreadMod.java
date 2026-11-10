package vn.dread;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DREAD - Kẻ Lột Da theo dõi bạn trong bóng tối. Chạm vào nó -> xuống Địa Ngục.
 * Vượt 4 thử thách, nhận 1 mảnh vỡ. Đủ 10 mảnh -> ghép ảnh -> cô ấy được giải thoát -> game tự tắt.
 */
public class DreadMod implements ModInitializer {

    public static final int FULL = 1023;
    public static final Identifier PUZZLE_PACKET = new Identifier("dread", "puzzle");

    public static final EntityType<FlayedEntity> FLAYED = Registry.register(Registries.ENTITY_TYPE,
        new Identifier("dread", "flayed"),
        FabricEntityTypeBuilder.create(SpawnGroup.MONSTER, FlayedEntity::new)
            .dimensions(EntityDimensions.fixed(0.7f, 2.1f)).fireImmune().trackRangeBlocks(64).build());

    public static final Item[] FRAGMENTS = new Item[10];

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final String[] WHISPERS = {
        "Tôi thấy bạn.", "Đừng ngoảnh lại.", "Bạn không nên xuống đây.",
        "Nó đang đứng ngay sau lưng bạn.", "Sao bạn không trả lời?",
        "Có ai đó đang đếm bước chân của bạn.", "Hãy tìm mình... mình bị vỡ ra rồi."
    };
    private static final String[] FAKE_NAMES = {"Herobrine", "null", "Entity_303"};

    static class State {
        int cooldown = 20 * 60, dread = 0, mask = -1, endTimer = 0;
        int stepsLeft = 0, stepTimer = 0, breathTimer = 0;
        int heartbeats = 0, heartTimer = 0;
        FlayedEntity stalker = null;
        int stare = 0, hidden = 0, stalkerAge = 0;
    }

    @Override
    public void onInitialize() {
        for (int i = 0; i < 10; i++)
            FRAGMENTS[i] = Registry.register(Registries.ITEM, new Identifier("dread", "fragment_" + (i + 1)),
                new FragmentItem(new FabricItemSettings().maxCount(1)));
        FabricDefaultAttributeRegistry.register(FLAYED, FlayedEntity.createAttributes());

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) tickPlayer(p);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUuid();
            State s = STATES.remove(id);
            if (s != null) clearStalker(s);
            HellRealm.cleanup(id);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> HellRealm.cleanup(newP.getUuid()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> STATES.clear());
        // Địa Ngục không có quái tự nhiên: chỉ quái do mod tạo (có tag "dread") mới được tồn tại
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (world.getRegistryKey() == HellRealm.HELL && entity instanceof MobEntity
                && !entity.getCommandTags().contains("dread")) entity.discard();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
            CommandManager.literal("dread")
                .requires(src -> src.hasPermissionLevel(2))
                .then(CommandManager.argument("event", StringArgumentType.word()).executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    boolean ok = runEvent(p, state(p), StringArgumentType.getString(ctx, "event"));
                    if (!ok) ctx.getSource().sendFeedback(() -> Text.literal(
                        "Dùng: steps | torches | door | chat | heart | stalker | hell | fragment | puzzle | reset"), false);
                    return ok ? 1 : 0;
                }))));
    }

    // ------------------------------------------------------------ tiện ích dùng chung

    static State state(ServerPlayerEntity p) { return STATES.computeIfAbsent(p.getUuid(), k -> new State()); }

    public static int maskOf(ServerPlayerEntity p) {
        State s = state(p);
        if (s.mask < 0) s.mask = DreadData.get(p.getServer()).get(p.getUuid());
        return s.mask;
    }

    public static void sendPuzzle(ServerPlayerEntity p, int mask, boolean ending) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(mask);
        buf.writeBoolean(ending);
        ServerPlayNetworking.send(p, PUZZLE_PACKET, buf);
    }

    static void title(ServerPlayerEntity p, String main, String sub) {
        p.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 60, 20));
        p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(sub)));
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(main)));
    }

    /** Nhận 1 mảnh vỡ chưa có. Đủ 10 -> sau vài giây phát màn kết thúc. */
    static void grantFragment(ServerPlayerEntity p) {
        DreadData data = DreadData.get(p.getServer());
        int mask = data.get(p.getUuid());
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < 10; i++) if ((mask & (1 << i)) == 0) free.add(i);
        if (free.isEmpty()) return;
        int k = free.get(p.getRandom().nextInt(free.size()));
        mask |= 1 << k;
        data.set(p.getUuid(), mask);
        State s = state(p);
        s.mask = mask;
        p.giveItemStack(new ItemStack(FRAGMENTS[k]));
        title(p, "§cMảnh vỡ #" + (k + 1), "§7" + Integer.bitCount(mask) + "/10 - chuột phải để xem bức ảnh");
        play(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, p.getPos(), 1.2f, 0.7f);
        if (mask == FULL) s.endTimer = 20 * 6;
    }

    /** Kẻ Lột Da chạm vào bạn. */
    static void dragToHell(ServerPlayerEntity p) {
        play(p, SoundEvents.ENTITY_ENDERMAN_SCREAM, p.getPos(), 2.0f, 0.5f);
        play(p, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, p.getPos(), 1.0f, 0.6f);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 100, 0));
        State s = state(p);
        clearStalker(s);
        s.dread += 2;
        s.cooldown = 20 * 45;
        HellRealm.enter(p);
    }

    // ------------------------------------------------------------ vòng lặp chính

    private static void tickPlayer(ServerPlayerEntity p) {
        State s = state(p);
        ServerWorld w = p.getServerWorld();

        if (w.getRegistryKey() == HellRealm.HELL) { HellRealm.tick(p); return; }

        if (s.endTimer > 0 && --s.endTimer == 0) { sendPuzzle(p, FULL, true); return; }
        if (maskOf(p) == FULL) return; // cô ấy đã được giải thoát: hết ám

        if (!p.interactionManager.getGameMode().isSurvivalLike() || w.getRegistryKey() != World.OVERWORLD) {
            clearStalker(s);
            return;
        }
        tickSteps(p, s);
        tickHeart(p, s);
        if (s.stalker != null) tickStalker(p, s, w);

        if (w.getLightLevel(p.getBlockPos()) > 7) return;
        if (--s.cooldown > 0) return;
        s.cooldown = Math.max(20 * 15, 20 * 100 - s.dread * 20 * 6) + w.random.nextInt(20 * 40);
        triggerRandom(p, s, w);
    }

    private static void triggerRandom(ServerPlayerEntity p, State s, ServerWorld w) {
        s.dread++;
        int r = w.random.nextInt(100);
        boolean done = false;
        if (r < 20) { startSteps(s); done = true; }
        else if (r < 34) done = eventTorches(p, w);
        else if (r < 46) done = eventDoor(p, w);
        else if (r < 58) { eventChat(p); done = true; }
        else if (r < 70) { eventHeart(p, s); done = true; }
        else if (s.dread >= 2) done = eventStalker(p, s, w);
        if (!done) startSteps(s);
    }

    private static boolean runEvent(ServerPlayerEntity p, State s, String name) {
        ServerWorld w = p.getServerWorld();
        return switch (name) {
            case "steps" -> { startSteps(s); yield true; }
            case "torches" -> eventTorches(p, w);
            case "door" -> eventDoor(p, w);
            case "chat" -> { eventChat(p); yield true; }
            case "heart" -> { eventHeart(p, s); yield true; }
            case "stalker" -> eventStalker(p, s, w);
            case "hell" -> { HellRealm.enter(p); yield true; }
            case "fragment" -> { grantFragment(p); yield true; }
            case "puzzle" -> { sendPuzzle(p, maskOf(p), false); yield true; }
            case "reset" -> { DreadData.get(p.getServer()).set(p.getUuid(), 0); s.mask = 0; yield true; }
            default -> false;
        };
    }

    // ------------------------------------------------------------ bước chân

    private static void startSteps(State s) { s.stepsLeft = 8; s.stepTimer = 0; }

    private static void tickSteps(ServerPlayerEntity p, State s) {
        if (s.breathTimer > 0 && --s.breathTimer == 0)
            play(p, SoundEvents.ENTITY_PLAYER_BREATH, behind(p, 1.2), 1.0f, 0.6f);
        if (s.stepsLeft <= 0 || --s.stepTimer > 0) return;
        play(p, SoundEvents.BLOCK_STONE_STEP, behind(p, 1.0 + s.stepsLeft), 1.0f,
            0.7f + p.getRandom().nextFloat() * 0.2f);
        s.stepsLeft--;
        s.stepTimer = 11;
        if (s.stepsLeft == 0) s.breathTimer = 50;
    }

    // ------------------------------------------------------------ đuốc, cửa, chat, tim

    private static boolean eventTorches(ServerPlayerEntity p, ServerWorld w) {
        List<BlockPos> list = new ArrayList<>();
        BlockPos c = p.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(c.add(-10, -5, -10), c.add(10, 5, 10))) {
            Block b = w.getBlockState(pos).getBlock();
            if (b == Blocks.TORCH || b == Blocks.WALL_TORCH) list.add(pos.toImmutable());
        }
        if (list.isEmpty()) return false;
        Collections.shuffle(list);
        int n = Math.min(list.size(), 2 + w.random.nextInt(3));
        for (int i = 0; i < n; i++) {
            BlockPos t = list.get(i);
            w.setBlockState(t, Blocks.AIR.getDefaultState());
            play(p, SoundEvents.BLOCK_FIRE_EXTINGUISH, Vec3d.ofCenter(t), 1.0f, 0.8f);
        }
        return true;
    }

    private static boolean eventDoor(ServerPlayerEntity p, ServerWorld w) {
        List<BlockPos> list = new ArrayList<>();
        BlockPos c = p.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(c.add(-12, -3, -12), c.add(12, 3, 12))) {
            BlockState st = w.getBlockState(pos);
            if (st.getBlock() instanceof DoorBlock && st.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER)
                list.add(pos.toImmutable());
        }
        if (list.isEmpty()) return false;
        BlockPos d = list.get(w.random.nextInt(list.size()));
        BlockState st = w.getBlockState(d);
        ((DoorBlock) st.getBlock()).setOpen(null, w, st, d, !st.get(DoorBlock.OPEN));
        return true;
    }

    private static void eventChat(ServerPlayerEntity p) {
        if (p.getRandom().nextInt(3) == 0) {
            p.sendMessage(Text.translatable("multiplayer.player.joined",
                FAKE_NAMES[p.getRandom().nextInt(FAKE_NAMES.length)]).formatted(Formatting.YELLOW));
        } else {
            p.sendMessage(Text.literal("<???> " + WHISPERS[p.getRandom().nextInt(WHISPERS.length)])
                .formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
        }
    }

    private static void eventHeart(ServerPlayerEntity p, State s) {
        s.heartbeats = 12;
        s.heartTimer = 0;
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 240, 0, false, false, false));
    }

    private static void tickHeart(ServerPlayerEntity p, State s) {
        if (s.heartbeats <= 0 || --s.heartTimer > 0) return;
        play(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, p.getPos(), 1.5f, 1.0f);
        s.heartTimer = 6 + s.heartbeats * 2;
        s.heartbeats--;
    }

    // ------------------------------------------------------------ KẺ LỘT DA rình mò

    private static boolean eventStalker(ServerPlayerEntity p, State s, ServerWorld w) {
        if (s.stalker != null) return false;
        Vec3d look = p.getRotationVector();
        double base = Math.atan2(look.z, look.x);
        for (int i = 0; i < 10; i++) {
            double ang = base + Math.toRadians(p.getRandom().nextInt(121) - 60);
            double dist = 18 + p.getRandom().nextInt(9);
            BlockPos pos = findStandable(w, p.getX() + Math.cos(ang) * dist, p.getZ() + Math.sin(ang) * dist, p.getBlockY());
            if (pos == null) continue;
            FlayedEntity v = FLAYED.create(w);
            if (v == null) return false;
            place(v, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, p);
            v.addCommandTag("dread");
            v.setAiDisabled(true);
            v.setSilent(true);
            v.setInvulnerable(true);
            v.setPersistent();
            w.spawnEntity(v);
            s.stalker = v;
            s.stare = 0; s.hidden = 0; s.stalkerAge = 0;
            return true;
        }
        return false;
    }

    private static void tickStalker(ServerPlayerEntity p, State s, ServerWorld w) {
        FlayedEntity v = s.stalker;
        if (v.isRemoved() || v.getWorld() != w || s.stalkerAge++ > 20 * 120) { clearStalker(s); return; }

        place(v, v.getX(), v.getY(), v.getZ(), p);
        double dist = p.distanceTo(v);
        if (dist <= 3.5) { dragToHell(p); return; }

        Vec3d to = v.getEyePos().subtract(p.getEyePos()).normalize();
        boolean looking = p.getRotationVec(1.0f).dotProduct(to) > 0.94 && p.canSee(v);

        if (looking) {
            s.hidden = 0;
            s.stare++;
            if (s.stare % 25 == 0) play(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, p.getPos(), 0.8f, 0.9f);
            if (s.stare > 60) {
                play(p, SoundEvents.ENTITY_GHAST_AMBIENT, v.getPos(), 0.6f, 0.5f);
                clearStalker(s);
                startSteps(s);
                s.stepsLeft = 6;
            }
        } else {
            s.stare = 0;
            if (++s.hidden % 50 == 0) advance(p, v, w);
        }
    }

    private static void advance(ServerPlayerEntity p, FlayedEntity v, ServerWorld w) {
        Vec3d dir = new Vec3d(p.getX() - v.getX(), 0, p.getZ() - v.getZ()).normalize();
        double step = 3 + p.getRandom().nextInt(3);
        BlockPos pos = findStandable(w, v.getX() + dir.x * step, v.getZ() + dir.z * step, v.getBlockY());
        if (pos != null) place(v, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, p);
    }

    private static void clearStalker(State s) {
        if (s.stalker != null) { s.stalker.discard(); s.stalker = null; }
    }

    // ------------------------------------------------------------ tiện ích

    private static void place(FlayedEntity v, double x, double y, double z, ServerPlayerEntity p) {
        float yaw = (float) (MathHelper.atan2(p.getZ() - z, p.getX() - x) * 180.0 / Math.PI) - 90f;
        v.refreshPositionAndAngles(x, y, z, yaw, 0f);
        v.setHeadYaw(yaw);
        v.setBodyYaw(yaw);
    }

    private static BlockPos findStandable(ServerWorld w, double x, double z, int y0) {
        int bx = MathHelper.floor(x), bz = MathHelper.floor(z);
        if (!w.getChunkManager().isChunkLoaded(bx >> 4, bz >> 4)) return null;
        for (int dy = 5; dy >= -8; dy--) {
            BlockPos pos = new BlockPos(bx, y0 + dy, bz);
            if (w.getBlockState(pos).isAir() && w.getBlockState(pos.up()).isAir()
                && w.getBlockState(pos.down()).isSolidBlock(w, pos.down())) return pos;
        }
        return null;
    }

    static Vec3d behind(ServerPlayerEntity p, double dist) {
        Vec3d look = p.getRotationVector();
        double len = Math.hypot(look.x, look.z);
        if (len < 1e-4) { look = new Vec3d(0, 0, 1); len = 1; }
        return p.getPos().add(-look.x / len * dist, 0.5, -look.z / len * dist);
    }

    /** Chỉ gửi âm thanh cho đúng người bị hù. */
    static void play(ServerPlayerEntity p, SoundEvent sound, Vec3d pos, float vol, float pitch) {
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(RegistryEntry.of(sound), SoundCategory.HOSTILE,
            pos.x, pos.y, pos.z, vol, pitch, p.getRandom().nextLong()));
    }
}
