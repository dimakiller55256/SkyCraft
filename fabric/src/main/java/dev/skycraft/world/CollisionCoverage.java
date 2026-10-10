package dev.skycraft.world;

/** A complete triangle/voxel region pair is required throughout the movement query. */
public final class CollisionCoverage {
    @FunctionalInterface public interface Known { boolean region(int x,int y,int z); }
    private CollisionCoverage() {}
    public static boolean ready(double minX,double minY,double minZ,double maxX,double maxY,double maxZ,Known known) {
        if (!Double.isFinite(minX+minY+minZ+maxX+maxY+maxZ) || maxX<minX || maxY<minY || maxZ<minZ) return false;
        if(Math.max(Math.max(Math.abs(minX),Math.abs(maxX)),Math.max(Math.abs(minZ),Math.abs(maxZ)))>30_000_000 || Math.max(Math.abs(minY),Math.abs(maxY))>30_000_000)return false;
        int x0=region(minX),y0=region(minY),z0=region(minZ);
        int x1=region(Math.nextDown(maxX)),y1=region(Math.nextDown(maxY)),z1=region(Math.nextDown(maxZ));
        if ((long)(x1-x0+1)*(y1-y0+1)*(z1-z0+1)>512 || x1<x0 || y1<y0 || z1<z0) return false;
        for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)if(!known.region(x,y,z))return false;
        return true;
    }
    private static int region(double value) { return (int)Math.floor(value/8.0); }
}
