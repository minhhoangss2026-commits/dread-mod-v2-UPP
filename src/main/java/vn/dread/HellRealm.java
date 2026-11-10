package vn.dread;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ĐỊA NGỤC: 4 thử thách được xây bằng code trong chiều không gian "dread:hell".
 *  1. Cầu Nham Thạch (parkour trên dung nham)  2. Đấu Trường Máu (3 đợt quái)
 *  3. Mê Cung Tối (có quái rình)               4. Bàn Thờ Máu (trùm) -> nhận 1 mảnh vỡ.
 * Độ khó tăng theo số mảnh vỡ đã có.
 */
public final class HellRealm {
    public static final RegistryKey<World> HELL =
        RegistryKey.of(RegistryKeys.WORLD, new Identifier("dread", "hell"));
    private static final int Y = 64;
    private static final Map<UUID, Run> RUNS = new HashMap<>();
    private static int counter = 0;

    private static final class Run {
        int stage, diff, baseX, ticks, timer, wave, minionTimer;
        ServerWorld retWorld; Vec3d retPos; float retYaw;
        BlockPos origin, start, goal;
        double fallY = Y - 4;
        final List<FlayedEntity> mobs = new ArrayList<>();
        FlayedEntity boss; ServerBossBar bar;
    }

    private HellRealm() {}

    // ------------------------------------------------------------------ vào / ra

    public static void enter(ServerPlayerEntity p) {
        MinecraftServer server = p.getServer();
        if (server == null || RUNS.containsKey(p.getUuid())) return;
        ServerWorld hell = server.getWorld(HELL);
        if (hell == null) {
            p.sendMessage(Text.literal("§cKhông tìm thấy chiều không gian Địa Ngục. Hãy tạo thế giới MỚI để mod nạp dimension."));
            return;
        }
        Run r = new Run();
        r.retWorld = p.getServerWorld(); r.retPos = p.getPos(); r.retYaw = p.getYaw();
        r.diff = Integer.bitCount(DreadData.get(server).get(p.getUuid()));
        r.baseX = 1000 + (counter++ % 40) * 1000;
        RUNS.put(p.getUuid(), r);
        startStage(p, hell, r, 1);
    }

    public static void cleanup(UUID id) {
        Run r = RUNS.remove(id);
        if (r != null) clearAll(r);
    }

    private static void clearAll(Run r) {
        for (FlayedEntity m : r.mobs) m.discard();
        r.mobs.clear();
        if (r.boss != null) { r.boss.discard(); r.boss = null; }
        if (r.bar != null) { r.bar.clearPlayers(); r.bar = null; }
    }

    private static void finish(ServerPlayerEntity p, Run r, boolean success) {
        clearAll(r);
        RUNS.remove(p.getUuid());
        p.extinguish();
        p.clearStatusEffects();
        p.setHealth(p.getMaxHealth());
        p.teleport(r.retWorld, r.retPos.x, r.retPos.y, r.retPos.z, r.retYaw, 0f);
        if (success) DreadMod.grantFragment(p);
    }

    private static void returnToSpawn(ServerPlayerEntity p) {
        ServerWorld ow = p.getServer().getOverworld();
        BlockPos sp = ow.getSpawnPos();
        int y = ow.getTopY(Heightmap.Type.MOTION_BLOCKING, sp.getX(), sp.getZ());
        p.teleport(ow, sp.getX() + 0.5, y, sp.getZ() + 0.5, 0f, 0f);
    }

    // ------------------------------------------------------------------ vòng lặp

    public static void tick(ServerPlayerEntity p) {
        Run r = RUNS.get(p.getUuid());
        if (r == null) { returnToSpawn(p); return; }
        ServerWorld hell = p.getServerWorld();
        r.ticks++;

        if (r.ticks % 160 == 0 && p.getRandom().nextInt(2) == 0)
            DreadMod.play(p, SoundEvents.ENTITY_GHAST_SCREAM, DreadMod.behind(p, 14), 0.8f, 0.5f);

        if (p.getY() < r.fallY) { // rơi -> về đầu thử thách, mất máu
            p.teleport(hell, r.start.getX() + 0.5, r.start.getY(), r.start.getZ() + 0.5, p.getYaw(), 0f);
            p.damage(p.getDamageSources().generic(), 4f);
            p.setOnFireFor(2);
            return;
        }
        switch (r.stage) {
            case 1 -> { if (near(p, r.goal, 3.0, 1)) next(p, hell, r); }
            case 2 -> tickWaves(p, hell, r);
            case 3 -> { if (near(p, r.goal, 1.6, 2)) next(p, hell, r); }
            default -> tickBoss(p, hell, r);
        }
    }

    private static boolean near(ServerPlayerEntity p, BlockPos g, double rad, double dy) {
        double dx = p.getX() - (g.getX() + 0.5), dz = p.getZ() - (g.getZ() + 0.5);
        return dx * dx + dz * dz < rad * rad && Math.abs(p.getY() - (g.getY() + 1)) < dy + 1;
    }

    private static void next(ServerPlayerEntity p, ServerWorld hell, Run r) {
        DreadMod.play(p, SoundEvents.ENTITY_WITHER_SPAWN, p.getPos(), 0.6f, 0.7f);
        startStage(p, hell, r, r.stage + 1);
    }

    // ------------------------------------------------------------------ các màn

    private static void startStage(ServerPlayerEntity p, ServerWorld hell, Run r, int n) {
        for (FlayedEntity m : r.mobs) m.discard();
        r.mobs.clear();
        r.stage = n; r.wave = 0; r.timer = 60; r.minionTimer = 0;
        r.origin = new BlockPos(r.baseX + n * 200, Y, 0);
        Random rnd = new Random(r.baseX * 31L + n * 977L + r.diff);
        switch (n) {
            case 1 -> buildParkour(hell, r, rnd);
            case 2 -> buildArena(hell, r, rnd, 13, false);
            case 3 -> buildMaze(hell, r, rnd);
            default -> buildArena(hell, r, rnd, 16, true);
        }
        p.teleport(hell, r.start.getX() + 0.5, r.start.getY(), r.start.getZ() + 0.5, n == 1 ? -90f : 0f, 0f);
        String[] names = {"", "Cầu Nham Thạch", "Đấu Trường Máu", "Mê Cung Tối", "Bàn Thờ Máu"};
        String[] hints = {"", "Đừng rơi xuống dung nham.", "Giết hết chúng. 3 đợt.", "Tìm ánh sáng đỏ. Nó cũng đang tìm bạn.", "Hạ Kẻ Lột Da để lấy mảnh vỡ."};
        DreadMod.title(p, "§4Địa Ngục " + n + "/4: " + names[n], "§7" + hints[n]);
    }

    private static void buildParkour(ServerWorld w, Run r, Random rnd) {
        int d = r.diff, x = r.origin.getX(), y = Y;
        fill(w, x - 2, y, -2, x + 2, y, 2, Blocks.POLISHED_BLACKSTONE_BRICKS);
        int cz = 0, cy = y, last = x + 2;
        for (int i = 0; i < 8 + d; i++) {
            int h = (d >= 4 && rnd.nextInt(3) == 0) ? 0 : 1;
            int gap = d >= 3 ? 2 + rnd.nextInt(2) : 1 + rnd.nextInt(3);
            int cx = last + gap + 1 + h;
            int zs = h == 0 ? 1 : 2;
            cz = Math.max(-8, Math.min(8, cz + rnd.nextInt(2 * zs + 1) - zs));
            int roll = rnd.nextInt(5);
            if (roll == 0 && gap <= 2) cy++; else if (roll == 1) cy--;
            cy = Math.max(y - 2, Math.min(y + 3, cy));
            Block b = switch (rnd.nextInt(4)) {
                case 0 -> Blocks.NETHERRACK; case 1 -> Blocks.SOUL_SAND;
                default -> Blocks.BLACKSTONE; };
            fill(w, cx - h, cy, cz - h, cx + h, cy, cz + h, b);
            if (h == 1 && d >= 2 && rnd.nextInt(4) == 0) set(w, cx, cy, cz, Blocks.MAGMA_BLOCK);
            last = cx + h;
        }
        int ex = last + 2 + 1 + 3;
        fill(w, ex - 3, cy, cz - 3, ex + 3, cy, cz + 3, Blocks.CRYING_OBSIDIAN);
        fill(w, ex - 2, cy, cz - 2, ex + 2, cy, cz + 2, Blocks.POLISHED_BLACKSTONE);
        for (int sx : new int[]{-3, 3}) for (int sz : new int[]{-3, 3}) {
            fill(w, ex + sx, cy + 1, cz + sz, ex + sx, cy + 3, cz + sz, Blocks.BONE_BLOCK);
            set(w, ex + sx, cy + 4, cz + sz, Blocks.SHROOMLIGHT);
        }
        // hố dung nham có thành bao quanh
        int minX = x - 10, maxX = ex + 10, fy = y - 9;
        fill(w, minX, fy, -16, maxX, fy, 16, Blocks.BLACKSTONE);
        fill(w, minX + 1, fy + 1, -15, maxX - 1, fy + 1, 15, Blocks.LAVA);
        fill(w, minX, fy, -16, minX, y - 7, 16, Blocks.BLACKSTONE);
        fill(w, maxX, fy, -16, maxX, y - 7, 16, Blocks.BLACKSTONE);
        fill(w, minX, fy, -16, maxX, y - 7, -16, Blocks.BLACKSTONE);
        fill(w, minX, fy, 16, maxX, y - 7, 16, Blocks.BLACKSTONE);
        r.start = new BlockPos(x, y + 1, 0);
        r.goal = new BlockPos(ex, cy, cz);
    }

    private static void buildArena(ServerWorld w, Run r, Random rnd, int R, boolean boss) {
        int ox = r.origin.getX();
        for (int dx = -R - 2; dx <= R + 2; dx++) for (int dz = -R - 2; dz <= R + 2; dz++) {
            int d2 = dx * dx + dz * dz;
            if (d2 <= R * R) {
                Block b;
                float f = rnd.nextFloat();
                if (boss) b = f < 0.7f ? Blocks.POLISHED_BLACKSTONE : f < 0.9f ? Blocks.NETHER_WART_BLOCK : Blocks.BONE_BLOCK;
                else b = f < 0.62f ? Blocks.NETHERRACK : f < 0.77f ? Blocks.RED_NETHER_BRICKS : f < 0.9f ? Blocks.NETHER_WART_BLOCK : Blocks.BONE_BLOCK;
                set(w, ox + dx, Y, dz, b);
            } else if (d2 <= (R + 1.6) * (R + 1.6)) {
                fill(w, ox + dx, Y, dz, ox + dx, Y + 5, dz, Blocks.POLISHED_BLACKSTONE_BRICKS);
            }
        }
        for (int k = 0; k < 8; k++) { // cột xương + đèn
            int px = ox + (int) Math.round(Math.cos(k * Math.PI / 4) * (R - 2));
            int pz = (int) Math.round(Math.sin(k * Math.PI / 4) * (R - 2));
            fill(w, px, Y + 1, pz, px, Y + 5, pz, Blocks.BONE_BLOCK);
            set(w, px, Y + 6, pz, Blocks.SHROOMLIGHT);
        }
        r.start = new BlockPos(ox, Y + 1, -R + 3);
    }

    private static void buildMaze(ServerWorld w, Run r, Random rnd) {
        int n = 5 + r.diff / 2, gw = 2 * n + 1;
        boolean[][] wall = new boolean[gw][gw];
        for (boolean[] row : wall) java.util.Arrays.fill(row, true);
        Deque<int[]> st = new ArrayDeque<>();
        wall[1][1] = false; st.push(new int[]{1, 1});
        int[][] dirs = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        while (!st.isEmpty()) {
            int[] c = st.peek();
            List<int[]> opts = new ArrayList<>();
            for (int[] dd : dirs) {
                int nx = c[0] + dd[0], nz = c[1] + dd[1];
                if (nx > 0 && nz > 0 && nx < gw - 1 && nz < gw - 1 && wall[nx][nz]) opts.add(dd);
            }
            if (opts.isEmpty()) { st.pop(); continue; }
            int[] dd = opts.get(rnd.nextInt(opts.size()));
            wall[c[0] + dd[0] / 2][c[1] + dd[1] / 2] = false;
            wall[c[0] + dd[0]][c[1] + dd[1]] = false;
            st.push(new int[]{c[0] + dd[0], c[1] + dd[1]});
        }
        int ox = r.origin.getX();
        for (int i = 0; i < gw; i++) for (int j = 0; j < gw; j++) {
            set(w, ox + i, Y, j, rnd.nextInt(5) == 0 ? Blocks.BLACKSTONE : Blocks.POLISHED_BLACKSTONE);
            set(w, ox + i, Y + 4, j, Blocks.BLACKSTONE);
            if (wall[i][j]) {
                for (int h = 1; h <= 3; h++) {
                    float f = rnd.nextFloat();
                    set(w, ox + i, Y + h, j, f < 0.84f ? Blocks.POLISHED_BLACKSTONE_BRICKS
                        : f < 0.93f ? Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS : Blocks.NETHER_WART_BLOCK);
                }
            }
        }
        set(w, ox + gw - 2, Y, gw - 2, Blocks.CRYING_OBSIDIAN);
        set(w, ox + gw - 2, Y + 4, gw - 2, Blocks.SHROOMLIGHT); // ánh sáng đỏ dẫn đường
        r.start = new BlockPos(ox + 1, Y + 1, 1);
        r.goal = new BlockPos(ox + gw - 2, Y, gw - 2);
        int hunters = 1 + r.diff / 3;
        for (int k = 0; k < hunters; k++) {
            int cx, cz;
            do { cx = 1 + rnd.nextInt(n) * 2; cz = 1 + rnd.nextInt(n) * 2; } while (cx + cz < 10);
            spawn(w, r, ox + cx + 0.5, Y + 1, cz + 0.5, 40 + 3 * r.diff, 8);
        }
    }

    // ------------------------------------------------------------------ đợt quái & trùm

    private static void tickWaves(ServerPlayerEntity p, ServerWorld hell, Run r) {
        if (r.timer > 0) { r.timer--; return; }
        r.mobs.removeIf(m -> m.isRemoved() || !m.isAlive());
        if (!r.mobs.isEmpty()) return;
        if (r.wave >= 3) { next(p, hell, r); return; }
        r.wave++;
        int count = 2 + r.wave + r.diff / 3;
        for (int i = 0; i < count; i++) {
            double a = hell.random.nextDouble() * Math.PI * 2;
            spawn(hell, r, r.origin.getX() + 0.5 + Math.cos(a) * 9, Y + 1, Math.sin(a) * 9, 22 + 2 * r.diff, 6);
        }
        DreadMod.title(p, "§4Đợt " + r.wave + "/3", "§7Chúng đang tới...");
    }

    private static void tickBoss(ServerPlayerEntity p, ServerWorld hell, Run r) {
        if (r.boss == null) {
            if (--r.timer > 0) return;
            r.boss = spawn(hell, null, r.origin.getX() + 0.5, Y + 1, 9.5, 120 + 15 * r.diff, 10);
            r.bar = new ServerBossBar(Text.literal("Kẻ Lột Da - Chủ Nhân Địa Ngục").formatted(Formatting.DARK_RED),
                BossBar.Color.RED, BossBar.Style.NOTCHED_10);
            r.bar.addPlayer(p);
            DreadMod.play(p, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, p.getPos(), 1.0f, 0.5f);
            return;
        }
        if (r.boss.isRemoved() || !r.boss.isAlive()) { finish(p, r, true); return; }
        r.bar.setPercent(r.boss.getHealth() / r.boss.getMaxHealth());
        if (++r.minionTimer >= 300) {
            r.minionTimer = 0;
            r.mobs.removeIf(m -> m.isRemoved() || !m.isAlive());
            if (r.mobs.size() < 6)
                for (int i = 0; i < 2; i++) {
                    double a = hell.random.nextDouble() * Math.PI * 2;
                    spawn(hell, r, r.origin.getX() + 0.5 + Math.cos(a) * 10, Y + 1, Math.sin(a) * 10, 20, 5);
                }
        }
    }

    private static FlayedEntity spawn(ServerWorld w, Run r, double x, double y, double z, double hp, double dmg) {
        FlayedEntity e = DreadMod.FLAYED.create(w);
        e.refreshPositionAndAngles(x, y, z, w.random.nextFloat() * 360f, 0f);
        e.addCommandTag("dread");
        e.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(hp);
        e.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE).setBaseValue(dmg);
        e.setHealth((float) hp);
        e.setPersistent();
        w.spawnEntity(e);
        if (r != null) r.mobs.add(e);
        return e;
    }

    // ------------------------------------------------------------------ xây khối

    private static void set(ServerWorld w, int x, int y, int z, Block b) {
        w.setBlockState(new BlockPos(x, y, z), b.getDefaultState(), Block.NOTIFY_LISTENERS);
    }

    private static void fill(ServerWorld w, int x1, int y1, int z1, int x2, int y2, int z2, Block b) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(w, x, y, z, b);
    }
}
