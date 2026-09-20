package com.hexgodofstories.server;

import com.hexgodofstories.data.IllusoryWall;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a Borrowed Reality wall is to everything that is not a player: real.
 *
 * <p>No block is ever placed, so this registry is what the world consults instead. Pathfinding reads
 * {@link #blocksPath} and routes around the courses, a mob's sight is stopped at the face by
 * {@link #blocksSight} — which is what makes a hunter lose the player behind it and give up — and a
 * mob that walks into one is pushed back out of it each tick. Players are never affected: walking
 * through your own lie is how you know it is one.
 */
public final class IllusoryWalls {
    private IllusoryWalls() {}

    public record Wall(UUID owner,ResourceKey<Level> dimension,int scale,long expires,Set<Long> solid,Set<Long> avoid,AABB bounds,int axis) {}
    /** One standing wall per caster: raising another lets the previous one go. */
    private static final Map<UUID,Wall> WALLS=new ConcurrentHashMap<>();

    public static boolean active() {return !WALLS.isEmpty();}
    public static Wall of(UUID owner) {return WALLS.get(owner);}

    /** Registers the courses a commit just raised, replacing whatever that caster had standing. */
    public static Wall raise(ServerPlayer owner,BlockPos origin,int scale,int seed,float yaw,long expires) {
        Set<Long> solid=new HashSet<>(),avoid=new HashSet<>();
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
        int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        Map<Long,Integer> tops=new HashMap<>();
        for(IllusoryWall.Placement placement:IllusoryWall.build(scale,seed,yaw)) {
            BlockPos at=origin.offset(placement.offset());
            solid.add(at.asLong());avoid.add(at.asLong());
            tops.merge(columnKey(at),at.getY(),Math::max);
            minX=Math.min(minX,at.getX());minY=Math.min(minY,at.getY());minZ=Math.min(minZ,at.getZ());
            maxX=Math.max(maxX,at.getX());maxY=Math.max(maxY,at.getY());maxZ=Math.max(maxZ,at.getZ());
        }
        if(solid.isEmpty())return null;
        // Two courses of clearance above every column, so nothing decides to path along the parapet
        // of a wall that would drop it straight through the moment it stepped on.
        for(Map.Entry<Long,Integer> column:tops.entrySet()) {
            int x=(int)(column.getKey()>>32),z=(int)(long)column.getKey();
            for(int y=1;y<=2;y++)avoid.add(BlockPos.asLong(x,column.getValue()+y,z));
        }
        AABB bounds=new AABB(minX,minY,minZ,maxX+1,maxY+1,maxZ+1);
        Wall wall=new Wall(owner.getUUID(),owner.level().dimension(),IllusoryWall.clamp(scale),expires,solid,avoid,bounds,bounds.getXsize()<=bounds.getZsize()?0:1);
        WALLS.put(owner.getUUID(),wall);
        return wall;
    }

    public static boolean dismiss(UUID owner) {return WALLS.remove(owner)!=null;}
    public static void reset() {WALLS.clear();}

    /** @return true when these coordinates sit inside a wall, or on the clearance above one. */
    public static boolean blocksPath(Level level,int x,int y,int z) {
        if(WALLS.isEmpty())return false;
        long key=BlockPos.asLong(x,y,z);
        for(Wall wall:WALLS.values())if(wall.dimension.equals(level.dimension())&&wall.avoid.contains(key))return true;
        return false;
    }

    /** @return true when a wall stands between these two points. */
    public static boolean blocksSight(Level level,Vec3 from,Vec3 to) {
        if(WALLS.isEmpty())return false;
        for(Wall wall:WALLS.values()) {
            if(!wall.dimension.equals(level.dimension()))continue;
            if(wall.bounds.clip(from,to).isPresent())return true;
        }
        return false;
    }

    /** Expiry, and the shove that keeps a mob on its own side of a wall it believes in. */
    public static void tick(ServerLevel level) {
        if(WALLS.isEmpty())return;
        long now=level.getGameTime();
        WALLS.values().removeIf(wall->wall.dimension.equals(level.dimension())&&wall.expires<=now);
        if(now%2!=0)return;
        for(Wall wall:WALLS.values()) {
            if(!wall.dimension.equals(level.dimension()))continue;
            double centre=wall.axis==0?(wall.bounds.minX+wall.bounds.maxX)/2:(wall.bounds.minZ+wall.bounds.maxZ)/2;
            double thickness=(wall.axis==0?wall.bounds.getXsize():wall.bounds.getZsize())/2;
            for(Mob mob:level.getEntitiesOfClass(Mob.class,wall.bounds.inflate(1,0,1),Mob::isAlive)) {
                AABB box=mob.getBoundingBox();
                if(!box.intersects(wall.bounds))continue;
                double at=wall.axis==0?mob.getX():mob.getZ();
                double delta=at-centre;
                if(Math.abs(delta)<1e-4)delta=wall.axis==0?-mob.getLookAngle().x:-mob.getLookAngle().z;
                if(Math.abs(delta)<1e-4)delta=1;
                double push=thickness+mob.getBbWidth()/2+.02-Math.abs(delta);
                if(push<=0)continue;
                double step=Math.signum(delta)*Math.min(push,.3);
                double dx=wall.axis==0?step:0,dz=wall.axis==0?0:step;
                if(!level.noCollision(mob,box.move(dx,0,dz)))continue;
                mob.setPos(mob.getX()+dx,mob.getY(),mob.getZ()+dz);
                Vec3 motion=mob.getDeltaMovement();
                mob.setDeltaMovement(wall.axis==0?new Vec3(0,motion.y,motion.z):new Vec3(motion.x,motion.y,0));
                mob.hurtMarked=true;
                // A mob that has just walked into a wall that is not there should stop insisting on
                // the route it was taking, or it grinds against the face until the lie expires.
                if(mob.getNavigation().isInProgress()&&mob.getRandom().nextInt(4)==0)mob.getNavigation().stop();
            }
        }
    }

    /** Sight is only stopped for the creatures the lie is told to; players see through their own eyes. */
    public static boolean fooled(Object viewer) {return viewer instanceof Mob&&!(viewer instanceof Player);}

    private static long columnKey(BlockPos pos) {return (long)pos.getX()<<32|pos.getZ()&0xffffffffL;}
}
