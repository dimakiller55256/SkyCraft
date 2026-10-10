package dev.skycraft.network;

import dev.skycraft.world.SkyDig;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.ByteBufCodecs;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DugCodecTest {
    @Test void largestSnapshotKeepsWorldsNegativeSectionsAndBits() {
        var sections=new ArrayList<SkyDig.DugSection>();
        for(int i=0;i<SkyDig.MAX_DUG_SECTIONS;i++) {long[] bits=new long[64];bits[i%64]=1L<<(i%63);sections.add(new SkyDig.DugSection(i+1,-1-i,bits));}
        var b=Unpooled.buffer();
        try {
            SkyDig.DugColumn.STREAM_CODEC.encode(b,new SkyDig.DugColumn(List.copyOf(sections)));
            assertTrue(b.readableBytes()>1024*1024,"Largest accepted snapshot needs the explicit larger packet limit");
            assertTrue(b.readableBytes()<4*1024*1024);
            var decoded=SkyDig.DugColumn.STREAM_CODEC.decode(b);
            assertEquals(sections.size(),decoded.sections().size());
            for(int i=0;i<sections.size();i++){assertEquals(sections.get(i).world(),decoded.sections().get(i).world());assertEquals(sections.get(i).sectionY(),decoded.sections().get(i).sectionY());assertArrayEquals(sections.get(i).bits(),decoded.sections().get(i).bits());}
            assertEquals(0,b.readableBytes());
        } finally {b.release();}
    }
    @Test void truncatedAndOversizedCountsAreRejectedBeforeAllocating() {
        for(int count:new int[]{1,SkyDig.MAX_DUG_SECTIONS+1,-1}) {
            var b=Unpooled.buffer();try{ByteBufCodecs.VAR_INT.encode(b,count);assertThrows(IllegalArgumentException.class,()->SkyDig.DugColumn.STREAM_CODEC.decode(b));}finally{b.release();}
        }
    }
}
