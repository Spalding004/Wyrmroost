package WolfShotz.Wyrmroost.util.io;

import WolfShotz.Wyrmroost.Wyrmroost;
import WolfShotz.Wyrmroost.content.io.screen.base.ContainerScreenBase;
import WolfShotz.Wyrmroost.util.network.messages.EntityRenameMessage;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.IGuiEventListener;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.Entity;
import net.minecraft.util.text.StringTextComponent;

public class NameFieldWidget extends TextFieldWidget implements IGuiEventListener
{
    private final Entity entity;

    public NameFieldWidget(FontRenderer font, int posX, int posY, int sizeX, int sizeY, ContainerScreenBase<?> screen)
    {
        super(font, posX, posY, sizeX, sizeY, screen.prevName);
        this.entity = screen.dragonInv.dragon;

        setEnableBackgroundDrawing(false);
        setMaxStringLength(16);
        setTextColor(16777215);
        setText(getMessage());
        screen.getChildren().add(this);
    }
    
    @Override
    public void tick()
    {
        super.tick();
        setEnableBackgroundDrawing(isFocused());
        
        if (!isFocused() && !entity.getDisplayName().getUnformattedComponentText().equals(getText()))
            setText(entity.getDisplayName().getUnformattedComponentText());
    }
    
    @Override
    public boolean keyPressed(int p1, int p2, int p3)
    {
        if (isFocused() && p1 == 257)
        {
            if (!entity.getName().getUnformattedComponentText().equals(getText()))
            {
                StringTextComponent name = getText().isEmpty() ? null : new StringTextComponent(getText());
                Wyrmroost.NETWORK.sendToServer(new EntityRenameMessage(entity, name));
            }
            setFocused2(false);
        }
        
        return super.keyPressed(p1, p2, p3);
    }
}