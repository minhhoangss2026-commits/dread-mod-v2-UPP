package vn.dread.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Ghép đủ 10 mảnh -> bức ảnh hoàn chỉnh -> cô ấy được giải thoát -> GAME TỰ TẮT. */
public class EndScreen extends Screen {
    private int t = 0;

    public EndScreen() { super(Text.literal("...")); }

    @Override public boolean shouldPause() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void tick() {
        if (++t >= 20 * 20 && this.client != null) this.client.scheduleStop(); // tắt game
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xFF000000);
        float a = Math.min(1f, (t + delta) / 90f);
        int h = this.height - 70, w = h * 64 / 160, x = (this.width - w) / 2;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        ctx.setShaderColor(1f, 1f, 1f, a);
        ctx.drawTexture(PuzzleScreen.PIC, x, 16, w, h, 0, 0, 64, 160, 64, 160);
        ctx.setShaderColor(1f, 1f, 1f, 1f);
        int y = this.height - 44;
        if (t > 110) ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Mình nhớ ra rồi..."), this.width / 2, y, 0xFFAAAAAA);
        if (t > 200) ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Cảm ơn bạn đã tìm thấy mình."), this.width / 2, y + 12, 0xFFAA2222);
        if (t > 300) ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Giờ mình có thể đi rồi."), this.width / 2, y + 24, 0xFFFFFFFF);
    }
}
