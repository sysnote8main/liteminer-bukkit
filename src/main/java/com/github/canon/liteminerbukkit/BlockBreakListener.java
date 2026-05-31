package com.github.canon.liteminerbukkit;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.*;

public class BlockBreakListener implements Listener {

    private final LiteminerBukkit plugin;
    private static final int MAX_BLOCKS = 64;
    private static final float EXHAUSTION_PER_BLOCK = 0.025f;

    // ShapelessのBlockFamilyマッチング用 (石炭↔深層石炭鉱石 など)
    private static final Map<Material, Set<Material>> BLOCK_FAMILIES = new HashMap<>();

    static {
        registerFamily(Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Material.RAW_IRON_BLOCK);
        registerFamily(Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.RAW_GOLD_BLOCK);
        registerFamily(Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE, Material.RAW_COPPER_BLOCK);
        registerFamily(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE);
        registerFamily(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE);
        registerFamily(Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);
        registerFamily(Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE);
        registerFamily(Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE);
    }

    private static void registerFamily(Material... mats) {
        Set<Material> family = Set.of(mats);
        family.forEach(m -> BLOCK_FAMILIES.put(m, family));
    }

    /**
     * Shapeless用: BlockFamily.matches() の再現
     * 同じMaterial、またはファミリーマッチなら true
     */
    protected static boolean familyMatches(Material from, Material to) {
        if (from == to) return true;
        Set<Material> family = BLOCK_FAMILIES.get(from);
        if (family != null && family.contains(to)) return true;
        family = BLOCK_FAMILIES.get(to);
        return family != null && family.contains(from);
    }

    /**
     * Walker.shouldMine() の再現:
     * - 空気・液体はfalse
     * - Hardness < 0 (ベドロックなど) はfalse
     * - それ以外はtrue
     */
    protected static boolean shouldMine(Block block) {
        Material mat = block.getType();
        if (mat == Material.AIR || mat == Material.CAVE_AIR || mat == Material.VOID_AIR) return false;
        if (mat.isLegacy()) return false;
        // 液体
        if (mat == Material.WATER || mat == Material.LAVA) return false;
        // Hardness -1 = indestructible (bedrock etc.)
        return !(block.getType().getHardness() < 0);
    }

    public BlockBreakListener(LiteminerBukkit plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        LiteminerBukkit.LiteminerState state = plugin.getPlayerState(player);

        if (!state.keybindPressed()) return;

        Block startBlock = event.getBlock();
        Material targetType = startBlock.getType();
        ItemStack tool = player.getInventory().getItemInMainHand();

        Set<Block> blocksToMine = getBlocksToMine(player, startBlock, targetType, state.shape());

        // 原点に近い順でソート (元のMODと同様)
        List<Block> sorted = new ArrayList<>(blocksToMine);
        sorted.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(startBlock.getLocation())));

        for (Block block : sorted) {
            if (block.equals(startBlock)) continue; // イベントで自然に壊れる

            if (!shouldMine(block)) continue;

            // クリエイティブモードは耐久・満腹度処理をスキップ (オリジナルと同じ)
            if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
                block.setType(Material.AIR);
                continue;
            }

            // 耐久値チェック: 残り2以下で停止 (preventToolBreaking の再現)
            if (tool.getItemMeta() instanceof Damageable dm) {
                int maxDur = tool.getType().getMaxDurability();
                if (maxDur > 0) {
                    int damage = dm.getDamage();
                    if (maxDur - damage <= 2) break;
                }
            }

            block.breakNaturally(tool, true);

            // 満腹度消耗: causeFoodExhaustion の再現
            // Bukkit に直接の API はないため、exhaustion を加算して飢餓連鎖を正しく発生させる
            // (4.0 を超えると満腹度/サチュレーションを消費するバニラ動作はサーバーのフードティックが処理)
            float newExhaustion = player.getExhaustion() + EXHAUSTION_PER_BLOCK;
            player.setExhaustion(Math.min(newExhaustion, 40.0f)); // cap to prevent overflow

            // ツール耐久消耗: hardness == 0 のブロック (草花など即時破壊) は消費しない
            // (オリジナルの state.getDestroySpeed() != 0.0f チェックに相当)
            if (block.getType().getHardness() != 0.0f && tool.getItemMeta() instanceof Damageable dm) {
                dm.setDamage(dm.getDamage() + 1);
                tool.setItemMeta(dm);
            }
        }
    }

    // ===== シェイプ別ブロック収集 =====

    private Set<Block> getBlocksToMine(Player player, Block origin, Material targetType, int shapeIndex) {
        return switch (shapeIndex) {
            case 1 -> smallTunnel(player, origin);
            case 2 -> staircaseUp(player, origin);
            case 3 -> staircaseDown(player, origin);
            case 4 -> threeByThree(player, origin);
            default -> shapeless(origin, targetType);
        };
    }

    /**
     * Shapeless: ShapelessWalker の完全再現
     * 隣接ブロックをマンハッタン距離順にソートしてDFSで最大64個収集する
     * (BFSではなくDFS+距離ソートがオリジナルと同じ挙動)
     */
    private Set<Block> shapeless(Block origin, Material targetType) {
        Set<Block> result = new HashSet<>();
        Set<Block> visited = new HashSet<>();
        result.add(origin);
        shapelessDFS(origin, origin, targetType, result, visited);
        return result;
    }

    private static final int[][] NEIGHBOR_OFFSETS = {
        // 6方向 (cardinal)
        {0,1,0},{0,-1,0},{0,0,-1},{0,0,1},{1,0,0},{-1,0,0},
        // 水平斜め4方向
        {1,0,-1},{-1,0,-1},{1,0,1},{-1,0,1},
        // 上段斜め8方向
        {0,1,-1},{0,1,1},{1,1,0},{-1,1,0},{1,1,-1},{-1,1,-1},{1,1,1},{-1,1,1},
        // 下段斜め8方向
        {0,-1,-1},{0,-1,1},{1,-1,0},{-1,-1,0},{1,-1,-1},{-1,-1,-1},{1,-1,1},{-1,-1,1}
    };

    private void shapelessDFS(Block current, Block origin, Material targetType,
                              Set<Block> result, Set<Block> visited) {
        if (visited.size() >= MAX_BLOCKS) return;
        if (visited.contains(current)) return;
        if (!familyMatches(targetType, current.getType())) return;
        if (!shouldMine(current)) return;

        result.add(current);
        visited.add(current);

        // 隣接ブロックをマンハッタン距離(origin基準)順にソートしてDFS — オリジナルと同じ順序
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int cx = current.getX(), cy = current.getY(), cz = current.getZ();

        int[][] sorted = NEIGHBOR_OFFSETS.clone();
        Arrays.sort(sorted, Comparator.comparingInt(o ->
            Math.abs(cx + o[0] - ox) + Math.abs(cy + o[1] - oy) + Math.abs(cz + o[2] - oz)
        ));

        for (int[] off : sorted) {
            Block neighbor = current.getRelative(off[0], off[1], off[2]);
            shapelessDFS(neighbor, origin, targetType, result, visited);
        }
    }

    /**
     * Small Tunnel: TunnelWalker の完全再現
     * raytrace().getDirection().getOpposite() = 壁の内部方向 (プレイヤーから見て奥) に直進する
     * shouldMine()を満たすブロックを最大64個掘り進める (型不問)
     */
    private Set<Block> smallTunnel(Player player, Block origin) {
        Set<Block> result = new HashSet<>();
        result.add(origin);

        // getTargetBlockFace() は「ブロックの当たった面」= プレイヤー側の面
        // その逆 (opposite) = 壁の内部方向 = 掘り進む向き
        BlockFace face = getTargetBlockFace(player);
        // UP/DOWNは水平向き優先 (オリジナルはraytrace.getDirection().getOppositeで水平優先)
        BlockFace tunnelDir;
        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            tunnelDir = getHorizontalFacing(player);
        } else {
            tunnelDir = face.getOppositeFace(); // 例: 南面を叩いた → 北方向(壁の内側)へ
        }

        Block cursor = origin.getRelative(tunnelDir);
        while (result.size() < MAX_BLOCKS) {
            if (!shouldMine(cursor)) break;
            result.add(cursor);
            cursor = cursor.getRelative(tunnelDir);
        }
        return result;
    }

    /**
     * Staircase Up: StaircaseUpWalker の完全再現
     * 各ステップで cursor, cursor.above(), cursor.below() の3ブロックを判定し、
     * direction+UP 方向に進む。3つ全てが shouldMine() falseの場合のみ停止。
     */
    protected Set<Block> staircaseUp(Player player, Block origin) {
        return staircase(player, origin, true);
    }

    /**
     * Staircase Down: StaircaseDownWalker の完全再現
     * 各ステップで cursor, cursor.below(), cursor.below().below() の3ブロックを判定し、
     * direction+DOWN 方向に進む。3つ全てが shouldMine() falseの場合のみ停止。
     */
    protected Set<Block> staircaseDown(Player player, Block origin) {
        return staircase(player, origin, false);
    }

    protected Set<Block> staircase(Player player, Block origin, boolean isUp) {
        Set<Block> result = new HashSet<>();
        result.add(origin);

        BlockFace face = getTargetBlockFace(player);
        BlockFace stairDir = getStairDirection(player, face);

        Block cursor = origin;
        while (result.size() < MAX_BLOCKS) {
            Block alpha = cursor.getRelative(isUp ? BlockFace.UP: BlockFace.DOWN);
            Block beta = isUp ? cursor.getRelative(BlockFace.DOWN): alpha.getRelative(BlockFace.DOWN);

            boolean mineAbove = shouldMine(alpha);
            boolean mineCursor = shouldMine(cursor);
            boolean mineBelow = shouldMine(beta);

            // オリジナルと同じ: 3つ全てがfalseのときだけ停止
            if (!mineAbove && !mineCursor && !mineBelow) break;

            if (mineCursor && !result.contains(cursor)) addWithinLimit(result, cursor);
            if (mineBelow && !result.contains(beta))   addWithinLimit(result, beta);
            if (mineAbove && !result.contains(alpha))   addWithinLimit(result, alpha);

            cursor = cursor.getRelative(stairDir).getRelative(isUp ? BlockFace.UP: BlockFace.DOWN);
        }
        return result;
    }

    /**
     * 3x3: ThreeByThreeWalker の再現
     * 叩いた面に対して垂直な3x3平面を破壊する (型不問、shouldMine()準拠)
     */
    private Set<Block> threeByThree(Player player, Block origin) {
        Set<Block> result = new HashSet<>();

        BlockFace face = getTargetBlockFace(player);
        if (face == null) face = BlockFace.SOUTH;

        BlockFace up, down, left, right;

        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            up = BlockFace.NORTH; down = BlockFace.SOUTH;
            left = BlockFace.WEST; right = BlockFace.EAST;
        } else {
            up = BlockFace.UP; down = BlockFace.DOWN;
            left = rotateCounterClockwise(face);
            right = rotateClockwise(face);
        }

        Block[] plane = {
            origin.getRelative(left).getRelative(up),
            origin.getRelative(up),
            origin.getRelative(right).getRelative(up),
            origin.getRelative(left),
            origin,
            origin.getRelative(right),
            origin.getRelative(left).getRelative(down),
            origin.getRelative(down),
            origin.getRelative(right).getRelative(down)
        };

        for (Block b : plane) {
            if (shouldMine(b)) result.add(b);
        }
        return result;
    }

    // ===== ユーティリティ =====

    /** オリジナルの addIfWithinLimit() の再現 */
    protected static void addWithinLimit(Set<Block> set, Block block) {
        if (set.size() < MAX_BLOCKS) set.add(block);
    }

    /**
     * プレイヤーが見ているブロックの「当たった面」を返す
     */
    protected BlockFace getTargetBlockFace(Player player) {
        List<Block> twoBlocks = player.getLastTwoTargetBlocks(null, 10);
        if (twoBlocks.size() != 2 || !twoBlocks.get(1).getType().isSolid()) return BlockFace.SOUTH;
        Block targetBlock = twoBlocks.get(1);
        Block adjacent = twoBlocks.get(0);

        BlockFace f = targetBlock.getFace(adjacent);
        return f != null ? f : BlockFace.SOUTH;
    }

    /**
     * Staircase用: 叩いた面がUP/DOWNならプレイヤー水平向き、それ以外は逆面 (= 進む方向)
     */
    protected BlockFace getStairDirection(Player player, BlockFace face) {
        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            return getHorizontalFacing(player);
        }
        return face.getOppositeFace();
    }

    protected BlockFace rotateClockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> face;
        };
    }

    protected BlockFace rotateCounterClockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> face;
        };
    }

    protected BlockFace getHorizontalFacing(Player player) {
        float yaw = (player.getLocation().getYaw() % 360 + 360) % 360;
        if (yaw >= 45 && yaw < 135)  return BlockFace.WEST;
        if (yaw >= 135 && yaw < 225) return BlockFace.NORTH;
        if (yaw >= 225 && yaw < 315) return BlockFace.EAST;
        return BlockFace.SOUTH;
    }
}
