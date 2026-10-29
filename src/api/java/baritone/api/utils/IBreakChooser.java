/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.utils;

import java.util.List;

/**
 * Picks which block a process breaks next, out of every block it could break right now (Humanlike
 * Baritone fork).
 * <p>
 * Upstream, the builder breaks the first reachable block in a fixed scan order. With a chooser set
 * (see {@link baritone.api.IBaritone#setBreakChooser}), the builder collects every block it needs
 * to break that the player can reach from where they stand and lets the chooser pick, so a model
 * of a player's own order can decide.
 */
public interface IBreakChooser {

    /**
     * Called each tick the builder would break a block near the player.
     *
     * @param candidates The blocks to break that are within reach, in Baritone's own scan order;
     *                   never empty
     * @return The index of the block to break, or -1 for Baritone's own pick (the first)
     */
    int choose(List<BetterBlockPos> candidates);
}
