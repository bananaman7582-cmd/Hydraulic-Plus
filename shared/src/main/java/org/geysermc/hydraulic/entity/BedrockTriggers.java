package org.geysermc.hydraulic.entity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Decides when Bedrock should play each animation read off a mob.
 * <p>
 * Reading an animation is only half of it. A recording is a set of poses over time and says nothing
 * about the moment it belongs to, and Bedrock will not play one unless told what makes it true - so
 * an unbound animation sits in the pack, correct and never seen.
 * <p>
 * What there is to go on is the name the mod gave it. Bedrock decides these things with expressions
 * about the mob it is drawing, and two of them line up perfectly: a walk belongs to moving, and an
 * idle to simply existing. Those are worked out by the client from what it can already see, which
 * makes them both the cheapest and the most accurate.
 * <p>
 * Everything else is decided by the server and reaches the client as a number on the mob, so what is
 * left here is the question of which of the two kinds an animation is. The conditions further down -
 * sitting, swimming, flying - are kept for mobs whose animations the server cannot see beginning,
 * which in practice means the ones a mod drives entirely from its own client code. See
 * {@link AnimationIndex} for how the rest are played.
 */
public final class BedrockTriggers {
    /**
     * How strongly the walk plays, rather than whether it plays at all.
     * <p>
     * Bedrock reads what is written here as a weight and not a yes or no, so a plain comparison is
     * the harshest possible answer to the question: the stride is off, and then at full strength one
     * frame later, with the mob snapping between standing and walking every time it sets off or
     * stops. Rising with the mob's speed instead lets the walk come in over a few frames and settle
     * back out again, and a mob that is barely moving barely walks.
     */
    private static final String MOVING = "math.clamp(query.modified_move_speed * 12, 0, 1)";

    /**
     * Whether the mob is on the ground, for a mob that also flies.
     * <p>
     * {@code query.is_on_ground} alone was not enough: a Geyser entity is moved by the server rather
     * than falling under the client's own physics, and it answers no while a bird stands in a field -
     * which took the walk away entirely. Standing still vertically means the same thing and is worked
     * out from movement, so either answer being yes is taken as being on the ground.
     */
    private static final String GROUNDED =
            "math.max(query.is_on_ground, 1 - math.clamp(math.abs(query.vertical_speed) * 3, 0, 1))";

    /**
     * How strongly standing about plays: whatever is left over once the walk has had its share.
     * <p>
     * Both were played at full strength, and Bedrock adds what they ask for rather than choosing
     * between them. Each recording holds the whole pose rather than a difference from the other, so a
     * mob got both at once - a zombie's arms come out in front to a right angle while standing, and
     * the walk swings them from there again, which is how they ended up moving twice as far as they
     * should. Java never adds them; it works the pose out once and draws that.
     * <p>
     * Sharing the weight between the two restores it. A mob standing still is all idle, a mob at speed
     * is all walk, and in between they cross over.
     */
    private static final String STILL = "1 - " + MOVING;

    /**
     * Names for the animation that plays the whole time the mob exists.
     */
    private static final List<String> ALWAYS = List.of(
            "idle", "breath", "breathe", "blink", "float", "hover", "ambient", "base");

    /**
     * Names for the animation that plays while the mob is going somewhere.
     */
    private static final List<String> MOVES = List.of(
            "walk", "move", "run", "gallop", "crawl", "slither", "waddle", "trot");

    /**
     * Things a mob does on its own, which nothing outside it announces. These are put on a timer.
     */
    private static final List<String> AMBIENT = List.of(
            "eat", "graze", "sniff", "lick", "groom", "preen", "shake", "scratch", "yawn", "stretch",
            "dance", "sing", "call", "roar", "howl", "croak", "chirp", "look", "peck", "flap",
            "wag", "sneeze", "cough", "clean", "rest", "sway", "bob", "nod");

    /**
     * Things a mob does to something else, which only the server knows the moment of.
     */
    private static final List<String> ACTIONS = List.of(
            "attack", "bite", "maul", "swipe", "slam", "punch", "kick", "stomp", "charge", "lunge",
            "shoot", "spit", "sting", "throw", "grab", "snap", "headbutt", "gore", "pounce", "strike",
            "hurt", "die", "death", "spawn", "explode", "blast", "shockwave", "fire", "launch");

    /**
     * How long to leave between plays of a timed animation, in seconds.
     */
    private static final int PERIOD = 13;

    /**
     * How far apart to start each timed animation, so a mob with several does not do all of them at
     * once every time the timer comes round.
     */
    private static final int STAGGER = 5;

    private BedrockTriggers() {
    }

    /**
     * Whether the server should play this animation itself, at the moment the mob performs it.
     * <p>
     * Everything except walking and standing about, and deliberately so. The earlier version of this
     * asked whether the name looked like an action - whether it was called something from a list of
     * words meaning to strike or bite - and a mob doing anything the list had not thought of was
     * simply never animated. That is most of them: an elephant trumpets, a gorilla pounds its chest,
     * a raccoon looks puzzled, and none of those are on any list of words for attacking.
     * <p>
     * There is no need to guess. A mob's animation is server-side state, so the server can see it
     * begin - and something starting is exactly the moment to play it, whatever it is called. The
     * only ones to leave out are the two the Bedrock client works out for itself from whether the
     * mob is moving, which it does better than a packet could.
     */
    public static boolean isServerDriven(@NotNull String name) {
        return !matches(name, MOVES) && !matches(name, ALWAYS);
    }

    /**
     * Whether this animation is simply always running - breathing, or wings that never stop beating.
     * These need no condition, and stopping them when the mob moves would be wrong.
     */
    public static boolean isAlways(@NotNull String name) {
        return !matches(name, MOVES) && matches(name, ALWAYS);
    }

    /**
     * How strongly an always-running animation should play on a mob that also has a walk.
     *
     * @param alsoWalks whether the mob has a walk to share the weight with
     */
    @NotNull
    public static String always(boolean alsoWalks) {
        return alsoWalks ? STILL : "1.0";
    }

    /**
     * The condition under which Bedrock should play this animation.
     *
     * @param name the animation's name, as the mod called it
     * @param length how long the animation runs, in seconds
     * @param index which animation this is on the mob, used to stagger the timed ones apart
     * @return a Bedrock expression, or null if nothing here can decide when it plays
     */
    @Nullable
    public static String when(@NotNull String name, double length, int index) {
        return when(name, length, index, false);
    }

    /**
     * Whether this is an animation of the mob in the air.
     */
    public static boolean isFlight(@NotNull String name) {
        String lowered = name.toLowerCase(Locale.ROOT);
        return lowered.contains("fly") || lowered.contains("glide") || lowered.contains("soar");
    }

    /**
     * @param flies whether the mob also has an animation for being in the air, which changes what
     *              moving means: a bird gliding past is moving as surely as one walking, and asking
     *              only about speed plays both at once - folded wings and stepping legs laid over the
     *              glide, which is the whole of why a seagull in the air looked wrong
     */
    public static String when(@NotNull String name, double length, int index, boolean flies) {
        String lowered = name.toLowerCase(Locale.ROOT);

        if (matches(lowered, MOVES)) {
            return flies ? GROUNDED + " * " + MOVING : MOVING;
        }

        if (matches(lowered, ALWAYS)) {
            return null; // played unconditionally, which the caller writes differently
        }

        if (lowered.contains("sit") || lowered.contains("lay") || lowered.contains("lie")) {
            return "query.is_sitting";
        }

        if (lowered.contains("sleep")) {
            return "query.is_sleeping";
        }

        if (lowered.contains("swim") || lowered.contains("dive")) {
            return "query.is_in_water";
        }

        if (lowered.contains("fly") || lowered.contains("glide") || lowered.contains("soar")) {
            return "!query.is_on_ground";
        }

        if (matches(lowered, ACTIONS)) {
            return null; // the server plays these; see the class comment
        }

        if (matches(lowered, AMBIENT)) {
            return timed(length, index);
        }

        // Something unrecognised. Left unplayed rather than guessed at: a wrong guess is a mob doing
        // something strange at random, which is worse than one that simply does not do it
        return null;
    }

    /**
     * An expression that comes true for a moment every so often, so a mob idling in a field
     * occasionally does something.
     * <p>
     * The Bedrock client works this out itself from how long the mob has existed, which means it
     * needs nothing sent to it and costs nothing to run. Mobs do not all act at the same instant
     * because each of them has been alive for a different length of time.
     */
    @NotNull
    private static String timed(double length, int index) {
        // Long enough for the animation to finish, and a little over so it is not cut off mid-way
        double window = Math.max(0.5, length) + 0.25;
        int offset = index * STAGGER;

        return "math.mod(query.life_time + " + offset + ", " + PERIOD + ") < " + round(window);
    }

    private static boolean matches(@NotNull String name, @NotNull List<String> words) {
        String lowered = name.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (lowered.contains(word)) {
                return true;
            }
        }

        return false;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
