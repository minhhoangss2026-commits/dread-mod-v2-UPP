package vn.dread.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Bức ảnh 2x5 mảnh. Mảnh chưa có thì tối đen. */
public class PuzzleScreen extends Screen {
    static final Identifier PIC = new Identifier("dread", "textures/gui/picture.png");
    private final int mask;

    public PuzzleScreen(int mask) { super(Text.literal("Mảnh Vỡ Ký Ức")); this.mask = mask; }

    @Override public boolean shouldPause() { return false; }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xEE000000);
        int have = Integer.bitCount(mask);
        int t = Math.max(16, (this.height - 70) / 5);
        int x0 = (this.width - t * 2) / 2, y0 = 34;
        for (int i = 0; i < 10; i++) {
            int c = i % 2, r = i / 2, x = x0 + c * t, y = y0 + r * t;
            if ((mask & (1 << i)) != 0) {
                ctx.drawTexture(PIC, x, y, t, t, c * 32, r * 32, 32, 32, 64, 160);
                ctx.drawBorder(x, y, t, t, 0xFF600000);
            } else {
                ctx.fill(x, y, x + t, y + t, 0xFF120508);
                ctx.drawBorder(x, y, t, t, 0xFF300000);
                ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("?"), x + t / 2, y + t / 2 - 4, 0xFF661111);
            }
        }
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal("Mảnh Vỡ Ký Ức  " + have + "/10"), this.width / 2, 12, 0xFFAA2222);
        String hint = have >= 10 ? "Cô ấy đã nhớ ra tất cả..."
            : "Còn " + (10 - have) + " mảnh. Kẻ Lột Da đang giữ chúng.";
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(hint), this.width / 2, y0 + t * 5 + 8, 0xFF888888);
    }
}
