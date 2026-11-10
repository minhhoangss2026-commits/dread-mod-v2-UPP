package vn.dread;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** Mảnh vỡ ký ức. Chuột phải để xem bức ảnh đang được ghép. */
public class FragmentItem extends Item {
    public FragmentItem(Settings settings) { super(settings); }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        if (!world.isClient && user instanceof ServerPlayerEntity sp)
            DreadMod.sendPuzzle(sp, DreadMod.maskOf(sp), false);
        return TypedActionResult.success(user.getStackInHand(hand));
    }
}
