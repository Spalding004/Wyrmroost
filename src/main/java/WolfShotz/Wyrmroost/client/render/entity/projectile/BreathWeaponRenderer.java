package WolfShotz.Wyrmroost.client.render.entity.projectile;

import WolfShotz.Wyrmroost.entities.projectile.breath.BreathWeaponEntity;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.renderer.Atlases;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.model.ModelBakery;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

public class BreathWeaponRenderer extends EntityRenderer<BreathWeaponEntity>
{
    public BreathWeaponRenderer(EntityRendererManager renderManager) { super(renderManager); }

    @Override
    public void render(BreathWeaponEntity entity, float yaw, float partialTicks, MatrixStack ms, IRenderTypeBuffer typeBuffer, int packedLine)
    {
        if (entity.isBurning())
        {
            renderFire(ms, typeBuffer, entity);
//            return;
        }
    }

    @Override
    public ResourceLocation getEntityTexture(BreathWeaponEntity entity) { return null; }

    private void renderFire(MatrixStack ms, IRenderTypeBuffer typeBuffer, Entity entity)
    {
        TextureAtlasSprite fireSprite1 = ModelBakery.LOCATION_FIRE_0.getSprite();
        TextureAtlasSprite fireSprite2 = ModelBakery.LOCATION_FIRE_1.getSprite();
        ms.push();
        float width = entity.getWidth() * 1.4F;
        ms.scale(width, width, width);
        float x = 0.5F;
        float height = entity.getHeight() / width;
        float y = 0.0F;
        ms.rotate(renderManager.getCameraOrientation()); // Literally right here, is the only thing I changed. So stupid.
        ms.translate(0, 0, (-0.3f + (float) ((int) height) * 0.02f));
        float z = 0;
        int i = 0;
        IVertexBuilder vertex = typeBuffer.getBuffer(Atlases.getCutoutBlockType());

        for (MatrixStack.Entry msEntry = ms.getLast(); height > 0.0F; ++i)
        {
            TextureAtlasSprite fireSprite = i % 2 == 0? fireSprite1 : fireSprite2;
            float minU = fireSprite.getMinU();
            float minV = fireSprite.getMinV();
            float maxU = fireSprite.getMaxU();
            float maxV = fireSprite.getMaxV();
            if (i / 2 % 2 == 0)
            {
                float prevMaxU = maxU;
                maxU = minU;
                minU = prevMaxU;
            }

            fireVertex(msEntry, vertex, x - 0, 0 - y, z, maxU, maxV);
            fireVertex(msEntry, vertex, -x - 0, 0 - y, z, minU, maxV);
            fireVertex(msEntry, vertex, -x - 0, 1.4f - y, z, minU, minV);
            fireVertex(msEntry, vertex, x - 0, 1.4f - y, z, maxU, minV);
            height -= 0.45f;
            y -= 0.45f;
            x *= 0.9f;
            z += 0.03f;
        }

        ms.pop();
    }

    private static void fireVertex(MatrixStack.Entry msEntry, IVertexBuilder bufferIn, float x, float y, float z, float texU, float texV)
    {
        bufferIn.pos(msEntry.getMatrix(), x, y, z).color(255, 255, 255, 255).tex(texU, texV).overlay(0, 10).lightmap(240).normal(msEntry.getNormal(), 0.0F, 1.0F, 0.0F).endVertex();
    }
}
