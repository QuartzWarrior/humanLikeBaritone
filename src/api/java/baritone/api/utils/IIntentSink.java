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

import baritone.api.utils.input.Input;

import java.util.Set;

/**
 * Receives Baritone's intent each tick instead of Baritone applying it (Humanlike Baritone fork).
 * <p>
 * While a sink is set (see {@link baritone.api.IBaritone#setIntentSink}), Baritone doesn't rotate
 * the player and doesn't override the movement input or click blocks itself. The sink's owner
 * moves the real mouse and presses the real keys, and the game does the rest. Movements already
 * wait until the crosshair is on a block before asking to break or place it, so the slower,
 * humanized camera is waited for.
 */
public interface IIntentSink {

    /**
     * The rotation Baritone wants. Called whenever a process or movement updates its look target,
     * possibly several times a tick; the latest call wins.
     *
     * @param target        The wanted rotation
     * @param blockInteract Whether the rotation aims at a block to break or place
     */
    void look(Rotation target, boolean blockInteract);

    /**
     * The inputs Baritone holds this tick. Called once every game tick, possibly with none.
     *
     * @param held The inputs Baritone wants down
     */
    void inputs(Set<Input> held);
}
