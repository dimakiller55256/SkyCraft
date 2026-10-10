package dev.skycraft.net;
import dev.skycraft.world.SkyDig;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GameplayCodecTest {
    @Test void snapshotPreservesDimensionWorldAndBits(){
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {long[] bits=new long[64];bits[2]=1L<<63;
            var column=new SkyDig.DugColumn(List.of(new SkyDig.DugSection(0x3c,-2,bits)));
            var p=new DigSync.Snapshot("minecraft:overworld",-3,4,column);
            DigSync.Snapshot.CODEC.encode(b,p);var q=DigSync.Snapshot.CODEC.decode(b);
            assertEquals(p.dimension(),q.dimension());assertEquals(-3,q.x());assertEquals(4,q.z());
            assertArrayEquals(bits,q.column().bits(0x3c,-2));assertNull(q.column().bits(0x44,-2));assertEquals(0,b.readableBytes());
        }finally{b.release();}
    }
    @Test void rejectsInvalidSectionCount(){
        var b=Unpooled.buffer();try{net.minecraft.network.codec.ByteBufCodecs.VAR_INT.encode(b,Integer.MAX_VALUE);assertThrows(IllegalArgumentException.class,()->SkyDig.DugColumn.STREAM_CODEC.decode(b));}finally{b.release();}
    }
    @Test void guestHurtPreservesOriginForShield(){
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{
            var p=new SkyNet.Hurt(1,178.48f,0x123456,2,true,-12.5f,3f,42.25f);
            SkyNet.Hurt.CODEC.encode(b,p);assertEquals(p,SkyNet.Hurt.CODEC.decode(b));assertEquals(0,b.readableBytes());
            assertTrue(p.type().id().toString().endsWith("hurt_v2"));
        }finally{b.release();}
    }
}
