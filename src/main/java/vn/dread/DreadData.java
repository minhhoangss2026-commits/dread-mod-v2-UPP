package vn.dread;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Lưu bit-mask 10 mảnh vỡ của từng người chơi (không mất khi chết). */
public class DreadData extends PersistentState {
    private final Map<UUID, Integer> mask = new HashMap<>();

    public static DreadData get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager()
            .getOrCreate(DreadData::fromNbt, DreadData::new, "dread_data");
    }

    private static DreadData fromNbt(NbtCompound nbt) {
        DreadData d = new DreadData();
        NbtCompound m = nbt.getCompound("m");
        for (String k : m.getKeys()) d.mask.put(UUID.fromString(k), m.getInt(k));
        return d;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtCompound m = new NbtCompound();
        mask.forEach((k, v) -> m.putInt(k.toString(), v));
        nbt.put("m", m);
        return nbt;
    }

    public int get(UUID id) { return mask.getOrDefault(id, 0); }
    public void set(UUID id, int v) { mask.put(id, v); markDirty(); }
}
