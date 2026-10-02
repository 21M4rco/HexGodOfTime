## Complete Evisceration

- **The Deceiver's G.** With The Deceiver in your hand, G no longer opens Warping's destinations (put the sword away
  and it does again): it is Complete Evisceration, a tap, with a ten-second recovery of its own shown under the
  spell. You dash at the body you are looking at (up to nine blocks, a block a tick), the sword arm cocked back at the
  hip and the point straight ahead, your free hand reaching for it. When you reach it the arm is thrown out to its full
  length in a deep lunge and the blade goes in at the gut and out of the back. The dash and the thrust follow your look
  up and down, so the point goes in wherever you aim. Blood bursts out of the front round the blade and out of the back
  after the point, the wound pumps round the blade while it is in, and you hear the same blade-piercing recording as
  Gravity Grasp's stab.
- **If it lives through it**, it is held on the blade a moment, five hearts down and bleeding (two stacks, six
  seconds), and let off it as the blade is torn out, a gush out of both wounds, staggering back.
- **If it would die of it, it does not, yet.** The blade is torn back out of it, you step back off it, and the sword
  comes down from high on your left through it to your right hip, fast, the whole body behind it, and that kills it
  and cuts it in two along that line, as in your drawing. Any body: every mob, any size, a player, any mod's creature.
  Every client draws it twice through its own renderer, each time keeping only what lies on one side of the cut, so
  nothing about the body needs to be known. The cut faces are burned like a Scepter hole: red-hot as the blade leaves
  them, cooling through orange to a charred dark red, smoking, with the blood pouring out of them. The upper half
  slides off down the cut, thrown away from you, its top going over to the right; the lower half stands a beat, then
  buckles and goes over the far way. Both land on whatever is really under them with a wet thud, in pools of their own
  blood, lie there ten seconds and sink away. A totem of undying that saves a player from the cut leaves them cut,
  not halved.
- **Nothing reached** and the thrust goes into the air, with a three-second recovery. A shield turns it aside.

## The Deceiver cuts on the run, and the stab is heard going in and pours

- **Running cuts.** Attacking while sprinting with The Deceiver is no longer the same slam every time. Each attack is
  one of seven running cuts, one-handed, the body leaning into the run and the legs left running under it: flat across
  to the left, flat back across to the right at head height, down from the right shoulder to the left hip, down from
  high on the left to the right hip, rising from the left hip past the right shoulder, rising from the right hip past
  the left shoulder, and a rising cut up the middle to high overhead. Each starts on the side the last one ended, so
  the blade always goes back and forth, and the next is picked at random from the ones that do, never the last cut
  and never the one before it while another will do. The slam (the lunge and the two-handed cut straight down that
  drives the body into the ground) is still there: it comes down out of the rising cut, the sword already overhead.
  A cut lands on its fifth tick and the next can follow on the twelfth, so you keep running and keep cutting; a body
  hit is thrown the way the blade went and on ahead of you, and a rising cut lifts it.
- **The blood goes where the blade went.** Cut to the left and the blood is thrown to the left; to the right, to the
  right; up, down, and across the same way. `tools/generate_blades.py` holds every running cut's point to its blood
  through the game's own render chain, as it does for every other cut.
- **As much blood as anything throws.** Every running cut, and the slam, throws the heaviest blood the game draws,
  the same as Gravity Grasp tearing the knife out across the throat: the full sheet off the edge, the gobs, the spray,
  the spurts after and the pools.
- **Gravity Grasp's stab is heard going in, loud.** The moment the dagger goes into the neck, a recording of a blade
  piercing a body plays, on its own, from that tick (supplied by the mod's author; see SCEPTER_AUDIO_CREDITS.md). It
  is brought up in the file to well over the Scepter's blast, played twice at once so the two add together (no single
  sound in Minecraft goes past full volume), and carried twice as far.
- **And it pours.** Going in, the blood is forced on along the blade and gushes back out round it toward you, both
  as heavy as any cut's; held in, the wound wells three times as fast, spurts on every beat (half again as often,
  three times the blood) and on each push of the knife (nearly four times), and pools wider under the body every
  tick; torn out, two full sheets are thrown after the blade, high and low, with over three times the spray across
  the cut, the neck pumps heavy spurts for three seconds instead of two, and the pools run all the way along.

## Paradise's price, kept; the fire sword points and burns like its blade

- **Warping pools are oil, not a trapdoor.** Whatever touches the liquid, it takes: a foot over the rim, a corner of
  you over a block's corner of it, not just a body standing over its middle. It takes hold gently at the surface and
  harder the deeper you go (full by the knees), sideways movement through it is a heave, you are drawn slowly toward
  the middle, and the surface puckers in round you with a thick, sucking sound as you go down. Nothing suffocates in
  it at any point: going down, rising out on the other side, or just come out.
- **Veilstep opens a combo.** For a second and a half after the step (and after its transformed Behind You) you are gone:
  invisible to every eye (body, armour, what you hold, your name, no swirl of particles), every creature that was
  hunting you loses you, and none can take you up again until it is out. No longer than that.
- **What Paradise's candy takes stays taken.** Leaving the realm used to give the limbs back: the server had never
  given them, but the new world's client started the body afresh and was never told otherwise. Now it is told on every
  crossing; the limbs come back only with death.
- **No legs: a stump at the waist.** The body is the torso and head on the ground, a shorter box and a lower eye,
  rocking from side to side as it drags itself along at a crawl (it used to be frozen in place); it can only hop.
  One leg is a hobble with a weak hop.
- **No off-hand arm: no off hand.** Nothing stays in it (whatever was there goes back into the pack) and swapping hands
  does nothing.
- **No arms at all: nothing.** Nothing is struck, used, placed, broken, picked up, dropped, looked through in the pack,
  cast or conjured; you are told you have no arms. Choosing and flying still work. (The right arm is still the last to
  go.)
- **The burning Deceiver points.** Its pointing move never played: Player Animator's layer built with its modifiers in
  the constructor never hands them the animation, so the arm that follows the look held nothing. It is linked properly
  now: hold use and the arm thrusts the blade out along your look while the fire pours, and nothing else plays under it.
- **Its fire is the blade's own.** The stream is now made of the same tongues of flame as the burning blade, the same
  sheet and the same red, orange, white-hot and blue: they leave the point as long streaks running together into a
  stream, widen and lick as they slow, turn upward with their own heat into a rolling body of fire, and spread and climb
  where they strike; a white-hot tongue and blue licks at the point, and smoke off the spent fire.

## The Deceiver on fire

- **Drawing it burning.** Transformed, drawing The Deceiver lights it by hand: the blade held upright before your
  face, a palm run up its flat with the fire chasing it to the point, the hand flung away, and the sword whipped down
  and out, back over the shoulder and down again in a figure of eight, shaking the fire out along it, into a wide low
  hold. Your head stays your own and you can walk through it.
- **Its use key pours fire** (after Surtur's sword). While it burns there is no guard: hold use and the sword arm is
  thrust out along your look (it follows you up and down) and a jet of fire pours from the point to whatever you look
  at, sixteen blocks at most. A white-hot thread inside a thick, ragged column of fire that widens as it goes and
  streams outward; licks tearing off its sides and drifting on, cooling from gold to red; a blue-white flare where it
  leaves the steel; and where it strikes, a churning fireball rolling up and out off the surface, smoke lifting off
  it. Sweep it and its fire hangs and rolls behind. Everything in it, or round where it strikes, burns: fire damage
  (fire-proof things take none) and set alight, never more than forty hearts to any one body from one pour. It draws
  Temporal Energy all the while it pours. Whatever it kills comes apart the way Time Branch Unleashing unmakes a body,
  only fast. All of it is drawn by the mod itself, no vanilla particles.

- **Transformed, The Deceiver burns.** Hold it in the full transformation and the blade slowly catches, the fire
  creeping up from the guard to the point over a second and a half, and it burns for as long as it stays in your
  hand: switch away, drop it, dismiss it or let the transformation end and it goes out. The fire is after Beric
  Dondarrion's sword: the whole blade engulfed, tongues of flame rooted under the steel and rising round it the way
  the world's up is, whatever angle you hold it at, licking up, tearing away and fading as the next rises under it,
  red at the edges, orange through the body (orange against a bright sky too, not washed white), a white-hot core and
  blue where it touches the steel, the tallest plume over the point; the steel glows with it. Smoke rises off it and
  you hear it crackle; no pixel flames or sparks. It is drawn on the blade itself, so
  it is there in first person, in third, and through every move, stance and combo.
- **Telekinesis throws a touch harder.** About 15% faster off the hand and a little higher; where it lands hurts
  the same as before.
- **Its blows burn.** Once it has caught, every hit of it (attacks, the slam, the Master Cuts) sets what it strikes
  alight for half a second, with half a heart of burn dealt in the blow.

## The sword's slam and its rest, no more spins, a held stab, and far more blood

- **No more full turns.** The Master Cuts' second cut spun the whole body round 360°, and the cuts after it carried that
  turn on, so a combo cut short could unwind it the other way. It is now the Zwerchhau as the masters cut it, feet
  planted: the blade carried back past the left shoulder and swept flat across at head height, the hips turning into
  it. The Flurry's spin is likewise a low sweeping cut off bent knees. No blade move turns the body more than 45° now.
- **The guard's slap to the left no longer puts the arm behind your back.** Its arm had been solved swung up and back
  over the shoulder to point the blade across; it now reaches across the front of the chest, the blade upright. A
  new check fails the build tools if any forearm passes into the chest or head at any half tick of any move, and a
  pose a move returns to is always drawn with the same arm, so one move ends exactly where the next (or the stance)
  begins.
- **The charge's slam: attack while sprinting with The Deceiver.** Both hands on the grip, a lunge off the stride with
  the sword swung up overhead, and it comes straight down: nine damage in a wide reach ahead, the body driven into the
  ground and held there for a moment, the ground cracking under it, and the heaviest blood of all.
- **The Deceiver at rest.** Stand still with it in hand for a couple of seconds and you settle into a rest: weight on one
  leg, a hand on the hip, the sword hanging loose a little ahead with its point on the ground. Move, swing or guard
  and it comes up again. Your head stays your own, and first person is untouched.
- **Gravity Grasp, harder and slower to kill.** The pull is about three times stronger and reaches twenty-two blocks,
  tearing bodies off the ground so nothing walks away from it. The hole on your palm is now the Gravity Well's black
  hole in miniature: a black horizon with a ring of bent light, a tilted disk of fine turning rings from white-hot
  lilac to deep purple, and streaks of light falling in. And the cut is a held stab: the caught body is seized, the
  dagger forms in an icepick grip and goes into the side of its neck and stays there for a full second, held,
  leaned on and pushed deeper, the wound pumping round the blade, before it is torn out across the throat in a
  sheet of blood, the body stunned a second more.
- **Far more blood.** Every blade's cut, the combos, the slam and a thrown dagger's hit throw about three times the
  blood: a heavy sheet off the edge, thick gobs arcing out of the wound, a fine spray, then the wound pumping two or
  three spurts after the blade has gone, with many more pools spattered along the way it flew and under the body.

## Real blades, and combo starters

- **Every blade move is the whole body now.** The cuts, the combos and the attacks are posed on the player model with
  Player Animator's body, legs, head and elbows, not one arm: weight shifts and dips, steps and lunges, twists,
  the head keeping its eyes on the target, and the dagger flipping between a forward and an icepick grip. Each was
  checked on a model of the game's own render chain before it shipped (`tools/preview_blades.py` draws them,
  `tools/generate_blades.py` holds every cut's point to the side its blood flies, and the handle to the fist at every
  half tick of every move: Player Animator turns a held item about a point off the fist, so every turn of the wrist is
  pivoted back onto it). The grips were rebuilt round the
  fist, so the hand closes on the handle with handle showing either side and the pommel below, and both blades are
  bigger: the dagger about a block long in the hand, The Deceiver about a block and two thirds.
- **The Flurry** after the Winter Soldier's knife work: the knife flips into an icepick grip for a backhand across the
  throat, a hammer stab drives down at the collarbone, it flips back for a lunging thrust, a low sweeping cut, and a push
  kick, then a twirl of the knife as the hand drops.
- **The Master Cuts** (Vinland Saga), one-handed, the free arm working for balance: a stepping cut from over the
  shoulder with the free hand pointing the way in, a flat cut across at head height with the arm flung wide, a fencer's lunge
  with the free arm thrown up behind, and the launcher: down into a crouch and exploding upward with a rising cut that
  lifts the body.
- **The Deceiver's guard: hold use.** A sweep of the sword up and out, then down into Malenia's stance: upright and
  easy, the sword arm loose and the long blade angled down and out to the side, walking freely in it. Turning a shot or
  a blow aside is a violent full-stretch slap of the whole arm toward it, the body twisting into it. Any shot from the front is slapped back the way
  it came and becomes yours; any blow from the front is parried in a burst of sparks and a ring of steel, its striker
  thrown back a step. 1.5 energy a shot, 2.5 a blow. Sprinting with the sword drops into a charge, the blade trailing
  low behind. Its attacks are new too, each landing heavy: crit sparks, a deeper sound, a shove, and your own view
  kicking with the hit.
- **One conjured weapon at a time.** Forming any of them dissolves whatever else you had conjured.
- **Thrown daggers** stay point-first in what they hit (they used to slowly swing sideways as they sat there), and one
  left in a body that dies now falls to the ground and lies there before it fades, instead of hanging in the air.

- **The dagger is remade.** A long, double-edged fighting dagger with a fullered blade and green runes cut down the
  fuller (they shine in the dark, as do its emeralds), horned gold quillons, a green leather grip bound in gold wire
  and a stone-capped pommel; about a fifth bigger than before, in the hand, thrown and in first person. Its throw is
  unchanged. Tapping its key with the dagger already in hand now puts it away.
- **Flurry: hold Conjure Daggers' key** within reach of a body you are looking at. The body is held stunned while you
  cut it forehand, backhand, down from high on the right and rising back, then push-kick it away; a second of
  bleeding after, four hearts in all. Only your upper body plays the moves, so you walk, turn and circle freely; leave
  its reach or look away and the combo ends there. With no dagger in hand one forms for it with a flick of the wrist
  and is gone at the end; nothing enters your inventory. 6 s recovery.
- **The Deceiver replaces Twin Deceivers.** A long, very thin, fine sword in the dagger's style (nothing caged or swept
  about its hilt): a slender fullered blade with its runes, a slim cross guard whose quillons dip and curl up, a long
  grip for both hands. Tap to conjure it, tap again to put it away; its attacks are slower, longer cuts that hit
  harder than the dagger's. It is not thrown.
- **Master Cuts: hold The Deceiver's key**, after the German longsword masters: the Zornhau (the wrath cut, down from
  the right shoulder), the Zwerchhau (the thwart cut, flat across at the head), the Zornort (a thrust out of the bind)
  and an Unterhau rising from below that lifts the body off its feet for your follow-up. Five and a half hearts and a
  second's bleeding; the same free movement and the same rules for reach and looking. 8 s recovery.
- **Every cut bleeds the way it went.** Each of the combos' cuts and each ordinary attack with either blade throws the
  body's blood off toward the side the blade travelled: a cut to the right sprays right, a rising cut sprays up, a
  thrust goes out through the back, and it pools where it lands. (The moves were posed against where the blade's point
  really goes in the game's own held-item chain, and tools/generate_blades.py checks each against its blood.)
- **Bleeding cripples.** Whatever opened the wound (a blade, a thrown dagger, the Scepter's beam, a bite), a bleeding
  body is slowed to a quarter of its speed (slowness V), reels with nausea and cannot jump at all, for as long as it
  bleeds and two seconds after. None of it shows a potion's swirl of particles, and neither do the Scepter's stun and
  daze any more: the blood is all there is to see.
- **Fixed:** the Gotcha! Swarm's missiles could leave a ball of fire hanging in the air where one burst. And a Crown
  missile's crater now grows back from its floor up, so sand and gravel come back onto ground instead of falling in.

## Seven keys, a remade mantle, a standing sea

- **Seven bind slots on the bottom row of the keyboard: Z X C V B N M.** Pressing a slot's key casts its
  ability at once — one press, nothing to choose first (hold it for spells that are held). A spell with a second
  move makes it on the same key **held** instead of tapped: the Crown is Gotcha! on a tap and the crown on a hold,
  Anchor Being is the vanishing on a tap and Gravity Grasp on a hold, and the rest are listed below. R casts the
  last spell again and taps and holds the same way. **There is no alternate key any more**, except for Warping: G
  picks where the pool leads (or, inside a Warping realm, the way out), whatever spell is chosen.

  | Hold the key of | Second move |
  |---|---|
  | Living Projection | Aim at a foe to send every projection at it, or at nothing to dismiss them |
  | Sleight of Place | Place a projection at your aim |
  | Masquerade | Take the disguise off |
  | Emerald Throw | The charged throw that bursts where it lands |
  | Conjure Daggers | Flurry, the dagger's combo starter (see above) |
  | The Deceiver | Master Cuts, the sword's combo starter (see above) |
  | Scepter | Dismiss it |
  | Whispered Allegiance | Direct your charmed creatures at your aim |
  | Chosen Moment | Spare an ally from your stopped field |
  | Anchor Being | Gravity Grasp, for as long as you hold |
  | Crown of Barrels | The crown, for as long as you hold (tapped: Gotcha!) |

  Telekinesis lifts on a tap and **throws on the next tap**; U lets go gently. Borrowed Reality is held to raise
  the wall; U, with it chosen, takes it down. The slots are drawn
  as a strip over the ability panel, each under its key, with its recovery drawn down over it and the chosen one
  lit. Bind them in the archive (K): click a slot (shown under its key), then click an ability. A layout saved
  with the old eight slots keeps its abilities, in order, in the seven. The keys that used to sit on that row moved,
  under new names so an old options file cannot put them on top of a slot: **U** utility (release / seal / leave),
  **I** Stillness, **O** Personal Rewind, **Left Alt** Time Branch. All of them can be rebound in Controls.
- **Glorious Purpose remakes the spells.** While the full transformation is worn, the same key casts a bigger
  spell (the archive lists each, in gold; the panel names it while you wear the mantle). The daggers and the
  Scepter are left as they are.

  | Ability | Transformed | What it does |
  |---|---|---|
  | Living Projection | Living Legion | Two copies at a time, two past your usual limit; they hit twice as hard, and one struck down bursts in seidr shards (two hearts, blindness, slowness around it). |
  | Sleight of Place | Exchange | Trade places with the creature or player you aim at (30 blocks); it is left where you stood, turned away and reeling. 8 s. |
  | Masquerade | Impostor | The one you copy glows for ten seconds and every creature near it turns on it; a player is blinded. 20 s. |
  | Court of Lies | Mass Delusion | Vanish for five seconds while every creature within sixteen blocks turns on the nearest other creature for eight; players reel. 20 s. |
  | Borrowed Reality | Reality Made | Grows twice as fast, stands half as long again, and is real to everyone but you: players are shoved back, projectiles are swallowed. |
  | Emerald Throw | Emerald Storm | Five bolts fanned out, each turning after the nearest creature ahead of it. 2 s. |
  | Sovereign Push | Kneel | Everything within twelve blocks is hammered to the ground: three hearts, held three seconds, slowed after. 15 s. |
  | Telekinesis | Many Hands | Lift everything in a wide cone in front of you, up to twelve, arrows and thrown things in the air included (they become yours to throw back); cast again to throw them all. 6 s. |
  | Veilstep | Behind You | Step out right behind the creature or player you aim at, facing its back; aimed at nothing, twice as far. 5 s. |
  | Runic Ward | Mirror Ward | Six seconds of the veil, and three quarters of every blow returns to whoever dealt it; projectiles rebound. 15 s. |
  | Whispered Allegiance | Silver Tongue | Charm up to six creatures within ten blocks at once, for twice as long; a player you aim at drops what they hold. 15 s. |
  | Memory Echo | Total Recall | Everything living within forty eight blocks glows through walls for twelve seconds. 20 s. |
  | Personal Rewind | Return to Sender | Rewind as ever, and every blow you took in those ten seconds lands on whoever dealt it. |
  | Warping | Wide Open | No hold: a tap opens the pool at its full size where you look, for the full cost. |
  | Crown of Barrels | Gotcha! Swarm | A tap sends four small missiles that hunt the body you looked at (see below). |
  | Anchor Being | Grand Anchor | The sixty-block blast, as before. |

- **Gotcha! Swarm.** In the transformation, Gotcha! sends four of the Crown's missiles, shrunk to under half the
  size, instead of a gun. They are thrown out from beside your head, fan away, then turn and hunt the body you looked
  at — slow (barely faster than a sprint), never straight, turning no tighter than a few blocks across, so they can
  be outrun for a while or side-stepped into overshooting; after nine seconds they burst wherever they are. Each
  bursts on the first thing it meets: five hearts to a body it flies into, three at the heart of a small blast,
  a short scorch, no block broken. A shield turned toward one takes it. 10 s recovery, shared with Gotcha!.
- **The Void Sea stands up.** The moving swell is gone (its formula, its pull on swimmers and its overlay). The
  sea itself is shaped now: long, bent swells running one way, rising into heavy peaks some twenty blocks tall that
  come down smoothly, never steeper than a block up for a block along, built of water — whole blocks, and the last
  fraction of each column as water with less in it, so the surface follows the shape to an eighth of a block. Each
  chunk is built once, the first time it loads, old chunks and new alike, before anyone is sent it; the realm's
  water never flows, so the shape stays. The Pilgrim reads the real waterline wherever it is.
- **No realm is a life sentence.** Anything a Warping break drops into a realm is held there two minutes at most.
  Still alive when they are up, it is let out where it was first taken, rising out of a black puddle on the very
  spot; a player hears the countdown at a minute, thirty seconds and the last ten. The time and the place travel on
  the body itself, so logging out or a restart changes nothing. Keepers (anyone with Warping) are never held.

## Anchor Being — the first move of Glorious Purpose (replaces Temporal Threads)

- **Anchor Being (cast).** You vanish for ten seconds (body, armour, what you hold, cloak, name and shadow,
  from every eye) and in the same tick a double takes your place: where you stood, looking where you looked,
  holding what you held and, if you were walking, walking on. No sound, no flash, no gesture. Whatever was
  hunting you turns on it. It never fights: it watches anything hostile near it and otherwise wanders its
  ground, for up to twenty seconds. Strike it and it looks down, trembles as the ground round it shakes and a charge and a heartbeat build (under two
  seconds; three for a grand one), and bursts in a slow, swelling green blast that leaves smoke hanging:
  twenty hearts (half at the edge of nine blocks), twenty seconds of nausea, the ground shaking forty blocks
  across, no block broken and no shield stopping it. 25 energy; 30 s recovery. **Secret:** cast in the full
  transformation, the blast is sixty blocks across (twenty hearts out to twelve, half at thirty), the ground
  shakes all of it, and a moment before it goes anyone looking at it is blinded white; two minutes' recovery.
- **Gravity Grasp (hold Anchor Being's key).** Your arm goes out and a small black hole opens on your palm (the
  Gravity Well's in miniature), tearing everything you could harm within twenty-two blocks off its feet and
  dragging it in, harder the longer you hold (ten seconds at most). The first body it brings within arm's reach is
  seized: the hole closes, a dagger forms reversed in your hand, and you drive it down into the side of the neck
  and leave it there, the body held stunned on it, for a second, then tear it out across the throat: six hearts,
  ten seconds' bleeding and a second's more stun. Its own 12 s recovery, apart from Anchor Being's.

## The Crown of Barrels — Loki's last conjuration

- **Nothing in the hand.** Conjuration at 620 mastery, once the Scepter is unlocked; 200 Temporal Energy.
  Hold the cast key and both arms go up and stay up, and machine guns form one after another in an arch over
  you — the top one first, then outward down both sides — each built from its stock to its muzzle behind a
  burning edge. Fifteen of them: TACZ's M249, RPK and FN Evolys, alternating down each side so the arch
  mirrors. No item is given, and nothing ever enters your inventory.
- **They fire where you look.** Once all are formed they charge their handles and swing onto whatever you
  are looking at, following your aim as you turn, and fire for as long as you hold, a little over eight
  seconds: each gun its own gunner, opening up a moment after the others and firing ragged bursts of its own
  length at about its real rate (750, 630 and 750 rounds a minute, give or take), each spraying about the
  mark its own way with every round straying further. Tracers, muzzle flashes, a stream of brass, smoke off
  the barrels, dust and sparks where the rounds land.
- **The rounds hold; the missiles kill.** A round barely stings (a twentieth of a heart) but stuns for a
  second, and every round after it starts that second again, so a body kept under fire is held for as long
  as it is. A shield raised toward you takes the rounds, and the stun with them. Rounds go through up to four
  bodies but never through a block, and once a second a body under
  fire takes a tiny hole where a round went in (open thirty seconds at most) and bleeds a little. A body
  takes at most one round a tick however many barrels are on it, and is never knocked about by them. You
  walk at a third of your pace meanwhile and cannot jump or sprint.
- **Let go and it ends.** Released early, the guns simply come apart where they hang and the recovery is
  short (ten seconds, or twenty once they have fired).
- **Hold to the end.** The guns come apart as two big missiles form beside your head, and at thirteen seconds
  you throw your arms down and they go — slowly, pushed by their fire, never straight: each climbs out to its
  own side, swings across the other's path and corkscrews down onto the point you aimed at, trailing smoke.
  Whatever your fire was still holding when it stopped stays held until they land (six seconds at most). No
  shield stops a missile. A missile is forty hearts to the body it strikes; its blast is thirty to anything else within five blocks,
  less out to nine, and sets it burning; it blows a
  crater (a power-6 explosion's worth, asked of every mod that guards the world first) that knits itself shut
  a block at a time, the way Paradise's ground does, starting some eight seconds later. Nothing drops and
  nothing is lost. The ground shakes forty blocks across: a wave rolls out from the crater's edge, throwing
  each block of ground up and letting it settle (half a block near the crater, a little at the far edge),
  kicking up dust, and whoever is standing on it feels it roll under them. It is drawn only, on each client;
  no block moves.
- **Gotcha!** Tap the Crown's key while looking at a body and a single gun forms without a sound a little
  way behind its back, facing where its head was, then turns onto its head wherever it is now and fires: a
  perfect shot at the upper head, whatever the size of the body, that misses only if a block is in the way
  (and a shield takes it only if raised toward the gun itself). Ten hearts, a hole bigger than a round's
  (open thirty seconds at most) and five seconds of pouring; your right arm points at it meanwhile. The gun
  goes behind the body where you can see it form if it can (turned round to one side, nearer or higher
  when straight back is walled in or hidden behind the body itself), otherwise anywhere around it with room
  and a clear shot, and last of all over its head. No energy; its own seven-second recovery, apart from
  the crown's twenty. Nothing is spent with no body in the look or nowhere to put the gun; a miss is a miss.
- **Light to run.** The guns exist only on each client, drawn from the same numbers the server fires by
  (`ArsenalLayout`): a crown costs no network traffic while it fires, and the server only a handful of rays
  a tick. Each model is one draw from the GPU; flashes, tracers, flames and rings are a few quads each; sound,
  tracers, brass and landings are all capped.
- **TACZ's work, credited.** The guns, rocket, casings, muzzle flash and gun sounds are TACZ's own, unaltered
  (CC BY-NC-ND 4.0 — non-commercial, credit given, no modification); see `ARSENAL_CREDITS.md`. TACZ is not a
  dependency.

## 0.6.2 — The Scepter, remade, and stopped time that actually stops

- **A real staff instead of a cut-out.** The old Scepter was a flat tracing, a few millimetres of
  extruded outline, and the first-person pose turned those flat faces sideways to the eye, so what
  you held was mostly the edge of a piece of card. It is now a solid model generated by
  `scripts/generate_scepter.py`, re-traced from the reference render: a thick blade ground to a
  cutting edge with its fuller and its notch, the twin-pronged fork, the folded bracket arched over
  the stone, the brow block, the gold cradle and a real coil spring under the stone, the ladder
  rungs, five overlapping armour sheaths on the shaft, engraved panel lines and the flared foot.
  Size in the hand, grip and reach are exactly what they were.
- **It is shaded like metal.** Vanilla entity light has no highlights, which is why metal in
  Minecraft reads as painted plastic. `ScepterModel` lights every vertex each frame with a studio
  key, fill and a sky reflection and adds the key's glint on top, while the block and sky light
  still darken it in a cave. The stone is its own light: a burning core inside a translucent
  cellular shell, a halo, and blue light thrown onto the cage and blade that hold it — all of which
  swell as a charge builds. `tools/preview_scepter.py` renders the model with the game's own hand
  transform and field of view, which is how the poses were chosen.
- **First person, redone.** At rest it stands diagonally beside the view with the stone turned to
  you; to fire it thrusts forward with the head just under the crosshair and the blade arched over
  it, kicks back and climbs on each shot, and trembles in the hand while the stone fills.
- **Tap or hold.** Tap right click for a bolt, as fast as you like: a tap inside the recovery is
  queued and fires the moment it can, up to five a second. Hold to fill the stone over a second and
  a half and release a beam that is wider, louder, burns through up to six bodies and detonates
  whatever it finally reaches. A charge held for ten seconds lets itself go.
- **It leaves holes.** Every body a beam passes through keeps a cauterised hole through the limb it
  hit — an actual opening you can see the world through, white-hot at the rim, charred inside —
  that bleeds and closes again over seven to eleven seconds. Charged beams also stun and set on fire.
- **Real laser sounds.** Recorded public-domain sounds from Kenney's Sci-fi Sounds and Warfork's
  weapons (see `SCEPTER_AUDIO_CREDITS.md`): five takes of the shot, the Electrobolt discharge, a
  lasergun hum that rises with the charge, crunching impacts with a sub-bass boom under charged ones,
  and the sizzle of the beam going through a body.
- **Stopped time no longer shivers.** Bodies caught in a stop were pinned in place but their
  "previous tick" render copies were not, so each one slid a tick's worth of its last motion every
  tick and snapped back; limbs, dropped items, wings and idle animations did the same. Every one of
  those values is now pinned and a held body is drawn at one fixed instant, and a player caught in
  the stop can no longer jitter their view with the mouse.
- **Powers keep time on a server that has been running for weeks.** Effects were timed from the
  world's game time plus the frame's partial tick, and `ClientState.now()+partial` quietly adds in
  float. A float holds 24 bits: once a world has run 2^23 ticks (under five days of uptime) the
  partial tick rounds away, past 2^25 (about nineteen days) the clock moves in steps of four ticks,
  and it only gets coarser from there. Portals, Time Branch, meteors, grips, wounds and the HUD
  meters started late and moved in jumps on a long-running server, while a new single-player world
  looked perfect. The cape never showed it because its cloth runs on the wearer's own tick count.
  `ClientState.time`, `since`, `cycle` and `wave` now keep that arithmetic exact at any world age,
  and `verifyRenderClock` fails the build if a renderer adds a partial tick to the clock in float
  again.

## 0.6.0 — The portal is a pool now, and you fall into it

- **The shattered mirror is gone.** The break used to be a jagged union of an impact hole, branching
  cracks and loose slivers, and it read as glass because that is what it was. The portal is now
  something poured: a pool of liquid that is not water, spreading outward from where the caster put
  it and running further for as long as they keep holding. Closed outline, no corners, no points —
  ninety six directions each with their own distance, all of it driven by low harmonics of the
  angle, so it cannot grow a spike and the build fails if it ever does.
- **It spreads rather than scales.** Every direction has its own moment of starting to move and its
  own pace: early in a hold it is a lopsided bead, half way a broad lobed pool with one side still
  creeping, at full charge everything the charge bought. Nothing retreats while the key is held, and
  the shape at full charge is demonstrably not the shape at a third of it with a bigger number in
  front. The furthest it can run is exactly the reach that was paid for.
- **It covers the ground rather than fighting it.** No block is destroyed or replaced. The liquid is
  one continuous sheet whose every vertex asks the floor in its own column how high it is, so it
  runs up a step, over a slab and down a stair and lies on all of them, and it sits a fraction above
  whatever it covers. What is drawn on it is thin on purpose — the point is that another world is
  visible through it — with rings travelling outward, a meniscus that brightens on whichever side is
  advancing, and beads thrown up at that edge in the colour of the floor being taken up.
- **The floor is held open, not merely opened once.** `Player.aiStep` assigns
  `noPhysics = isSpectator()` on every tick of every player, on the client and the server alike, and
  it does it before that tick's movement — so a grant handed out from a tick event was wiped before
  it stopped a single collision, and nobody, caster included, could get into their own portal. It is
  re-stated each tick between that assignment and the movement now.
- **The Void Sea looks like the Void Sea again.** Its view through a portal was still drawn against
  the waterline of 136 the realm had before it was rebuilt nine hundred and thirty five blocks deep,
  which put the whole ocean a hundred and sixty blocks below the hole you were looking through — so
  what you actually saw was the aperture's black backing with the pool's tint over it, a flat
  coloured puddle. It reads the realm's own constants now, so the next time the sea moves the view
  moves with it, and the shape circling under the surface is no longer buried inside an opaque box.
- **Stepping onto it no longer teleports you.** This is the change the rest of it exists for. A body
  over enough of the pool is given block pass-through, on the server and on its own client at once,
  and sinks: feet, legs, chest, and the dimension change waits until the eye is under the surface —
  so the world changes at the moment the view does, and a tall creature sinks further before it goes.
  "Enough of the pool" is nine points of the body's own footprint rather than one, so you can stand
  beside the rim, stand with one foot in it, and go through when most of you is over it.
- **You sink into it like quicksand, and you can fight your way out.** A body in the liquid does not
  fall — its descent is taken over by the pool's own slow rate and its sideways movement is dragged
  rather than stopped, so a running jump into the middle stops you dead and starts you going down,
  and a player's eye takes a little under two seconds to go under. Hammering the jump key lifts you,
  at a rate set from the sink rather than guessed: six presses a second exactly cancels it, so
  slower loses ground, faster climbs, and the deeper you already are the longer you have to keep it
  up. Presses are spent the tick they arrive, so there is no saving them up, and that one message
  skips the input throttle because a three-tick limiter would otherwise decide the contest itself.
  Get back above the rim by thrashing or by wading and the floor comes back under you.
- **The body clips itself, for free.** Entities are drawn before the portal, and the portal's backing
  sits at the floor's own height following the pool's exact outline, so the depth test paints out
  exactly the part of a body that has gone under and leaves the rest standing. First person and both
  third-person cameras, with nothing extra.
- **Nothing is reset on the way through.** Heading, pitch, whatever sideways movement survived the
  drag and the descent already under way all cross exactly. A dimension change sends an absolute
  position packet and an absolute position packet makes the client zero its own velocity, so a motion
  packet goes out immediately behind it, on the same tick, before a frame is drawn without it. A body
  that waded in sideways comes out still travelling that way. Minecraft's
  "downloading terrain" overlay is taken straight back down and a few frames of refraction — one ring
  crossing the view, a hair of chromatic split, no flash — cover the seam instead.
- **Whatever went through is still visible through the hole.** The far side's positions are sent to
  the clients that can see the portal, every few ticks and every tick while something is crossing, and
  drawn inside the aperture at their real coordinates in the destination. Stand at the edge and watch
  a body sink through, keep falling, and shrink away into the other world. They are positions, not
  entities: nothing exists on any server, nothing collides, nothing can be hit.
- **Held-open edge cases are handled rather than hoped about.** Several bodies can be part way through
  one pool at once, each with its own progress. A pool that expires with somebody in it finishes the
  crossing for anybody who is deep at that moment and stands anybody who is not back on top of the
  floor — where they are when it shuts, not the deepest they ever got, so fighting almost all the way
  out and then having the pool close over you is not the same as never having fought. A body that just
  came out of one cannot be caught by another for a second. And the watchdog for a client that never
  let go of the floor measures stillness rather than elapsed time, because a clock would hand a free
  escape to anybody who merely held on rather than climbed.

## 0.5.9 — Paradise, and Warping the other way round

- **The Falling World is gone. Paradise is where it was.** The destination that used to be an
  endless collapse is now a small, deliberately composed pocket world: fifteen floating islands on
  a climbing spiral inside ninety blocks of candy-coloured space, a hot spring at the middle of the
  central one, eleven waterfalls — three of them landing on shelves hung under the rims they leave,
  the rest falling out of the world — and a lilac sky carrying banded nebulae, four galaxies, five
  rainbows (two of them doubled, with the second bow's colours in the reverse order, as a real one
  runs) and a couple of dozen large, readable sweets drifting through it. Every island's outline is
  its own sum of four harmonics, so no two are the same shape and none of them is a circle.
- **The layout is measured against the movement rather than guessed at.** Gravity is a fifth of
  normal, which the build simulates tick by tick to find out what that actually buys: a running
  jump reaches four and a half blocks up and carries a little over fourteen along. Every island is
  then required to be inside that, no step outward is more than four blocks of climb, and
  `verifyParadise` fails the build if either stops being true. Falling off the edge is not a death:
  you are caught under the lowest keel and put back above the middle to drift down again.
- **The water pays you for swimming in it.** Any pool in the realm — the spring, the ponds, the
  cascades — grants Regeneration, Health Boost and **Candy Rush**, a new effect with its own icon
  that carries Speed II, Haste III and a body that will not keep still, reusing Minecraft's own
  freezing tremble so it reads instantly. It is entirely positive: nothing about it slows a step,
  blocks an input or costs a heart, and it all expires eleven seconds after you climb out.
- **Paradise cannot be spent.** Break any of its terrain and you keep what you mined; the hole
  closes itself some seconds later, exact block and exact state, with sugar gathering at the empty
  place first. Only the realm's own blueprint regenerates — a chest you carried in or a bridge you
  built is not in the index and is never touched — and nothing is ever scanned: the only positions
  the system knows are the ones something was seen to destroy.
- **And it is edible.** A block that came out of Paradise's own ground is marked as it drops and can
  be eaten, crouch and use, anywhere and for ever after. Five tiers, from the meadow up to somebody's
  candy cane, the better ones carrying a few seconds of something pleasant. None of it hurts.
- **The portal breaks like glass, with the floor coming off it.** The fracture already spread from
  one impact into branching cracks; now slivers of the actual surface tip up out of their own plane
  as it does, turn over, slide inward and go into the hole — cut from the colour of the block that
  is genuinely underneath, so a break over turf throws up turf, one over a beach throws up sand,
  and one spanning the line between them throws up both.
- **Warping now reaches inward as well as outward.** A new key pulls the realm chosen in the G menu
  to you instead of you to it: the ground where you are looking cracks open for two seconds, the way
  opens, and the creatures living in that realm climb up out of it a few ticks apart, each into its
  own collision-checked place, carried across with their health, equipment, names and modded data
  intact rather than copied. Everything about it — the destination, the point, the recovery, the
  search and what may be taken — is decided server-side from a bare keypress.
- **Hexor is not on the list and never will be.** The Void Sea's god is refused by class and by
  registered type, in the one server-side filter every transfer runs through, and asked again at the
  moment of the transfer rather than trusted from the keypress. `verifyHexor` now reads the source
  and fails the build if any of that stops being true. The sea keeps it.

## 0.5.6 — The Pilgrim stops knotting itself, and starts leaving the water

- **A spine, not a rope.** The body was reconstructed by replaying the head's own recorded path, so
  any curve the head could describe the body would reproduce — including curves far tighter than
  its own joint spacing. A hover, a tight orbit or a hard turn at low speed therefore folded a
  hundred and twenty six blocks of creature into a knot a metre across. Joints are now rigid six
  block links with a bend limit that widens toward the tail, so the path is followed as closely as
  a spine allows and no closer.
- **Turning is bounded by a radius.** Degrees per tick is the wrong limit for something this long:
  the same allowance is a graceful arc at attack speed and a pirouette while drifting. A target
  inside the turning circle is now carved past rather than spun at, and a reversal keeps more than
  half its speed, so it reads as a wide banked arc.
- **The attack geometry stopped asking for the impossible.** Stalk orbits start at forty six blocks,
  the crushing ring closes to thirty rather than four and a half, and the vortex holds thirty four,
  each turning at a rate its radius can actually be swum at.
- **It jumps.** Prey that leaves the water is answered by leaving the water: the creature reads
  where the target will be, solves the launch that meets it there, lines up underneath and throws
  itself, with one tail flick of drift permitted mid-air and nothing else. The existing deep breach
  uses the same solved impulse rather than trying to steer at a point in the sky.
- **New skin.** Every cube face stretches one tile of a fixed atlas, so a tile with edges draws
  those edges around all 1,550 cubes. The tiles are now seamless, low contrast and finely detailed
  — cold hide, lamellar bone, ribbed membrane, a luminous organ — and the rib blades stay swept
  along the hull instead of splaying out. Same geometry, same UV layout, reproducible through
  `python tools/generate_pilgrim_textures.py`.
- **It stopped twitching.** Three separate causes. The server sends the client a path checkpoint
  twice a second, anchored at the server's position, and the client pinned the body to it — but a
  client entity always trails the server one, so the whole creature lurched by the lag distance and
  snapped back the next tick. Checkpoints are now re-anchored onto the viewer's own copy, since
  only the shape was ever wanted from the server. The banking and the fin solver were both
  under-damped springs — poles at 0.88 and 0.86, ringing for one to two seconds — so a body being
  steered continuously never stopped ringing; both are now critically damped. And whether the
  creature counts as in the water is decided once a tick across an almost two block band instead of
  per caller on a bare threshold, so a body holding the waterline no longer flickers between
  swimming and airborne.
- **It stopped passing through itself.** The bend a joint may take now comes from its girth rather
  than its position along the body. The hull at the shoulder is eleven blocks across with its
  joints six apart, so it is wider than the gap between them and every degree there is geometry
  pushed through its neighbour; the tail is under a block across and can whip through three times
  as much. Seven degrees at the widest point, twenty one at the tip, and the steering's turn radius
  was raised to the fifty blocks that actually implies so the two agree.
- **It is no longer a torpedo.** Every speed the behaviour asks for is scaled in one place. The
  patterns were written in blocks per tick without much regard for the size of the thing carrying
  them, so a lunge crossed a hundred and twenty seven blocks a second — twenty three times a
  sprinting player. A cruise is now just under a sprint, a committed hunt two and a half times one,
  and a lunge five. Leaps are untouched: those are solved ballistics, not swimming.
- **The Void Sea drops you in.** Arrival was three blocks over the waterline, which put you in the
  water before you had seen any of it. It is now fifty: about two and a half seconds of open sky
  and an empty horizon on the way down, and the water still takes the fall.
- **Flight is confined to the fracture world.** It used to follow the mantle into every dimension,
  and sovereignty granted it again in eight of the nine Warping realms, which meant a corona you
  can climb out of, planes that cannot close on you, a collapse you do not fall with and an ocean
  whose hunter cannot reach you. Every realm is now survived from inside it. Your own pocket realm
  keeps flight, because falling off the edge is the only hazard there. Creative and spectator mode
  are untouched.

## 0.5.0 — HexGodOfStories

- **The mod is now HexGodOfStories.** Mod id, package, assets, keybinding category, creative tab and the command root all move: every command is `/hgos ...`. Existing saves are carried across — progression, unlocks, quick bars, sanctum plots and pending world restores are read from their old names once and rewritten under the new one, so nothing is lost and nothing is regenerated.
- **A new world grants nothing.** No HUD, no archive, no quick bar, no keys, no progression. Powers exist only after an operator runs `/hgos unlock <player> on`, and a player who has not been granted them accrues no mastery at all, not even silently. `/hgos unlock <player> off` takes everything back down again.
- **Borrowed Reality is a wall, in four sizes.** Hold the cast key and it grows Small → Medium → Big → Massive; release to commit whichever size it reached. The four previous building designs are gone.
- **Illusory walls stopped drifting.** The courses are drawn into their own buffer and flushed inside the camera transform that positioned them, instead of being left for Minecraft to flush later under a different matrix — which is what made them slide out of place as you turned. Each block is also lit from where it stands rather than full-bright, so borrowed stone sits in the scene's own light.
- **Creatures believe the wall.** Pathfinding treats its columns — and two courses of clearance above them — as blocked, so mobs walk around it and never try to cross the parapet. Sight is stopped at its face, so a hunter that loses you behind one forgets you the way it would behind real masonry. A mob that walks into one is turned back. Players still pass straight through: it is your lie, and it has no collision.
- **The quick slots say what is bound to them.** The archive's eight slots are two rows of four, each printing its number and the ability's full name, with the discipline's colour on its edge and a marker on the one currently selected. The in-game bar lists all eight by name with their recovery, and the readout names the slot the shown ability answers to.
- **No portal is left standing after an arrival.** A break now closes behind the traveller who opened it, a break is searched for across every level rather than only the one its caster is standing in — which is how one used to survive the crossing — and nothing can open a break in the tick or two after an arrival. An arrival is particles around where you land, and nothing else.

## 0.4.1 — Fracture modes, honest exits and a sanctum that fights back

- **The alternate key now configures the Fracture; the cast key runs it.** With the Fracture selected **and standing in your sanctum**, the alternate key opens a mode selector. Out in the world there is nothing to choose — the break leads one place, in — so the selector stays shut and the cast key is the whole control. What you pick there is saved and stays saved — through casts, dimension changes, death and a server restart — until you deliberately open the selector and pick something else. The cast key just does whatever is currently saved, as often as you press it. Every other spell keeps its ordinary alternate action.
- **Outside the sanctum, the cast key always takes you in.** Tap for a doorway to walk through, hold to drag everything within five blocks through with you, whatever mode is saved. The saved mode describes the way *out*, and waits until you are inside to mean anything. Holding the cast key on the way out now drags your cargo along too, to wherever you are going.
- **Six modes.** *Pull Into Fracture* is the original behaviour and keeps its tap-for-a-doorway, hold-for-a-vacuum distinction. *Travel Near Player* saves the person you chose and opens 8–12 blocks from them; if they log out the choice is dropped rather than leaving a stale coordinate. *Nearest Player* is resolved when you cast, not when you chose. *Nether*, *Respawn Point* (bed, anchor, or world spawn when neither stands) and *Normal Return* complete the set.
- **The live mode is never a guess.** The selector names it at the top and marks its row with a lit border and an ACTIVE marker; the bottom-left readout prints it beside the cast key.
- **Fixed: transported entities no longer return to where they were captured.** A break now carries the destination your saved mode resolved to when it was struck, and everything that walks through obeys it — you and every creature travelling with you. Drag a mob in at one place, leave somewhere else, and it surfaces beside you. Groups fan out around the arrival instead of stacking into one column.
- **Inside your own island you cannot be struck.** An attack is refused as it is declared, before any damage is worked out, and in the same instant you are several blocks away: thick nebula closes over where you stood for the blow to pass through, and a smaller bloom marks where you reform. Falls and the void still apply — this answers attackers, not gravity.
- **The island hunts whoever is troubling you.** Anything hunting you, angry at you, or that has recently drawn your blood draws a green celestial star: high, off-axis, wobbling as it closes, landing for about ten hearts. It calls no explosion, so the throne, the tree and the ground are never touched, and you are immune to every part of it.
- **Atmosphere, not redesign.** The island itself is untouched. Around it: a few drifting motes and a sparse nebula rim that flows slowly around the coast, sampling only the sections near you and idling entirely outside the dimension.

## 0.4.0 — Cloak, deception, universal shapes and time control

- **The cloak no longer stretches across the world.** Its solver now enforces two hard geometric limits after every pass and again on the frame the renderer draws, so no part of it can leave the shoulders by more than the fabric hanging above it, or leave the node above it by more than one stretched segment. Torso transforms that cannot be inverted are rejected rather than propagated, per-tick motion is capped, anything non-finite re-seeds the grid from the body, and a hem resting on the ground is dragged along instead of welded to the block it touched.
- **Projections fight on their own.** They pick out hostile creatures — vanilla or modded — anything already hunting their caster, and anyone who has recently drawn the caster's blood, including another player. Friendly, tame and neutral creatures are left alone unless they attack first.
- **A court carries mixed arms.** Copies spawn with a single dagger, twin daggers or the Scepter, and one always mirrors whatever the caster is actually holding. Staff wielders use the plain swing; dagger pairs alternate hands on a faster cadence.
- **Creatures cannot tell a copy from the original.** When something decides to hunt a keeper who has projections out, that decision is reopened across the keeper and every nearby copy and settled by a weighted draw on distance, line of sight and how much each body has recently hurt it. Being the real player counts for nothing. Existing aggression is redistributed the moment copies appear, and a copy that lands a blow draws retaliation onto itself.
- **Copies are hard to pick out by eye.** They carry the caster's skin, armour, worn head gear, elytra, name tag, harmless potion effects and cloak, mirror the caster's crouch and visible flourishes, and glance around instead of staring at their target.
- **Masquerade wears anything alive.** Any living entity in the game can be copied — vanilla or modded, passive, hostile, aquatic, flying, humanoid or not. The target's own saved state is captured, so a specific sheep keeps its colour and a specific modded creature keeps its variant instead of reverting to a default model. The borrowed body is driven from the player's movement, rotation, pose, swing and hurt state, and the player's own body is replaced rather than drawn underneath. Creatures respond to the costume: monsters ignore a monster shape and nothing hunts its own species, until the wearer attacks.
- **Thrown daggers stay where they land.** A chest hit rides the chest, an arm hit swings with the arm and a head hit turns with the skull, at any body size, and wounds bleed from the blade itself and leave drying pools on the ground behind a moving body.
- **Effects gather rather than switch on.** Every particle opens over its first ticks, ability effects release across a stretch of them, and the flight nebula condenses and disperses over about a second and a half. A nebula family built from the same noise field as that cloud gives the whole mod one material.
- **Time control has permanent keys.** Stillness, Rewind and Time Branch have dedicated keys. R resumes an active Stillness. B is unbound, and no resume entry appears in the HUD or archive.
- **Stillness holds the world, not just the mobs.** Bodies, arrows, thrown weapons, loose items, falling blocks, primed charges and orbs all stop, and weather and loose particles freeze where they stand — rain hangs in the air and resumes from the same phase.
- **Dilation is smooth.** Nothing has its ticks withheld any more; rates are scaled instead, so slow motion interpolates like ordinary movement rather than teleporting.

## 0.3.0 — Fracture and world-tree island

- Fracture inside the realm opens a return portal without an energy/mastery/cooldown requirement. Walk through to return to your own saved dimension, position and facing. A short crossing grace period prevents bouncing back into the entry portal. The arrival sigil remains a crouch-to-exit fallback.
- Dropped conjured daggers, the Scepter and time sticks dissolve before spawning or being picked up, including inventory tosses and death drops. Transferred weapons cannot be used by another player. Thrown attack daggers still embed and dissolve as before.
- Recorded Minecraft glass/weapon/material sounds replace the old synthetic audio. The portal's duplicate opening sound is removed. Resource-pack sample overrides are respected.
- Black metal horns, charcoal/gray outfit and a broader, flaring charcoal cape; shoulder attachment stays on the posed torso.
- Larger seeded mirror fractures with jagged apertures, uneven splinters and persistent branching cracks.
- A roughly 150-block-wide irregular island with more than twice the old land area, a deeply tapered rocky underside, rolling edges, sprawling/forked surface roots, hanging roots, luminous flower groves and a much larger branching tree.
- A thick carved throne, solid seat, wide stair approach, armrests and horned root crown. Right-click the seat to sit; sneak to stand.
- Existing plot coordinates and return sigils stay in place. V3 generation checkpoints resume after restart. The upgrade replaces matching old generated states and leaves other occupied blocks alone. Blocks a player placed that exactly match the original generated block at the same position cannot be distinguished from the original.

Install the normal `hexgodofstories-0.3.0.jar` from **Build HexGodOfStories**; `hexgodofstories-0.3.0-sources.jar` contains source code. The mod still targets Forge 1.20.1 and retains its existing Player Animator dependency. Client/server playtesting remains necessary; compilation does not certify visual or multiplayer behavior.

# HexGodOfStories

Minecraft **1.20.1**, Forge **47.4.10**, Java **17**. This is a development build, not a certified final release.

## Installation and build

Install Player Animator **1.0.2-rc1+1.20** (CurseForge file 4587214) on clients. Put the HexGodOfStories JAR on the server and each client. Run `gradle build` with Gradle 8.8, or download the mod artifact from the **Build HexGodOfStories** GitHub Action. The server does not need a graphics context.

## Controls

| Input | Action |
|---|---|
| K | Mastery archive |
| Z X C V B N M | The seven bind slots: **tap** one to cast what is bound to it, **hold** it for the spell's second move (or for spells that are held). Crown of Barrels: tap for Gotcha!, hold for the crown. Anchor Being: tap to vanish, hold for Gravity Grasp |
| R | Cast the last spell again, with the same tap and hold |
| G | **Warping destination** (inside a Warping realm: the way out), whatever spell is chosen. No other spell uses it |
| H | Glorious Purpose transformation |
| U | Utility: release held targets / seal your fracture / leave a realm / resume your time fields |
| I | **Stillness** — suspend the local battlefield; press again to resume |
| O | **Personal Rewind** |
| Left Alt | **Time Branch Unleashing** — tap for a charged fist or hold for the full beam |
| Wheel *(while gripping)* | Push or pull what telekinesis is holding |
| Attack / Use with a conjured weapon | Dagger combination / throw; Scepter two-heart hit with one-second bleed / hold at least a second to charge a piercing beam (100 blocks), up to ten seconds, when it fires itself |

Nobody has powers until an operator grants them: `/hgos unlock <player> on`. Until then the mod shows no HUD, opens no screen, answers no key and records no progression. Key mappings are configurable. Free your hands before conjuring. Successful spell use trains its discipline; training is rate limited. Temporal progression opens after 600 combined mastery in the four magical disciplines. Glorious Purpose opens after 800 Temporal Mastery.

The seven bind slots fill themselves as abilities unlock; to place one deliberately, open the archive, click a slot at the bottom (each is shown under its key), then click the ability you want bound to it.

The time controls are never bound to a slot. They are permanent commands on their own keys, shown as a fixed row at the bottom of the panel with their keys, readiness and recovery. All bindings are configurable in Minecraft's Controls menu. C and X are also vanilla's creative hotbar save/load activators; the slots read their keys directly, so both work.

## Notable abilities

- **Living Projection** — a decoy that looks, moves and fights like you, down to the name tag. It hunts hostile creatures and anyone who has attacked you, without being told. Creatures choosing between you and your copies cannot tell which is which: the choice is made on distance, sight and who has been hurting them, and never on which one is breathing. Holding its key sends every projection at whatever you are aiming at, or dismisses them all if you aim at nothing.
- **Telekinesis** — aim at a creature or player to lift it in a green glow of motes, your right arm held
  straight out toward it (first person too). One body at a time: cast again to throw it, or press Utility to let it go.
  The wheel pulls it closer or pushes it further, and whatever you throw takes
  damage if it hits a wall. Players can be held for three and a half seconds, creatures for fifteen.
- **Borrowed Reality** — hold the cast key and a wall grows where you aim, through Small, Medium, Big and Massive. No block is ever placed: viewers are handed an origin, a size and a seed and build the courses locally, while the server keeps the same columns in memory so that everything which is not a player treats the wall as masonry — mobs path around it, lose sight of you behind it and are turned back when they walk into it. You walk through your own lie; Utility, with the wall chosen, takes it down.
- **Fracture** — shatters the air where you look. From the outside it leads one place: in, taking anything beside you with it if you hold the key. From inside, where the break leads is chosen in the selector on the alternate key and stays chosen: a named player, whoever is nearest, the Nether, your bed, or the place you left. The break holds for seven seconds, and anything that walks through it follows your destination rather than its own history. Inside your sanctum you cannot be hit, and the island throws falling stars at anyone who tries.
- **The Deceiver** — a long, thin, fine sword in place of the old Twin Deceivers; its held key is the Master Cuts. Thrown daggers fly point-first, bury themselves in what they hit and open bleeding wounds before dissolving.
- **Masquerade** — wear any living thing in the game, vanilla or modded, keeping that individual creature's variant, colour, size and carried gear rather than its species' default. Creatures read the shape and mostly ignore it, until you attack one.
- **Stillness** — local suspension that decelerates into and out of a stop rather than snapping, and holds bodies, shots, loose items, falling blocks and the weather alike. Harm you deal to a suspended body is banked and lands the instant time resumes.
- **Dilation** — everything nearby runs at roughly a third speed, smoothly. Movement, attacks and arcing shots slow together; nothing stutters.
- **Crown of Barrels** — hold to raise fifteen machine guns in an arch over you that fire ragged bursts wherever you look, stunning, piercing bodies and leaving tiny bleeding holes; hold thirteen seconds and two missiles wander to the mark, forty hearts each on a direct hit, and blow a crater that grows back. Nothing is put in your hands.

## Development commands

All commands require operator permission level 2.

Granting and revoking powers:

- `/hgos unlock <player> on` — grant this player their powers
- `/hgos unlock <player> off` — take them back, tearing down anything they had standing

Everything else takes the player first, as `/hgos <player> ...`:

- `set <discipline> <0..1000>` / `add <discipline> <0..1000>`
- `unlock <ability>` / `unlock all`
- `energy <amount>`
- `transform true` / `transform false`
- `clear_illusions` / `clear_time` / `clear_bleed` / `clear_quick`
- `realm enter` / `realm exit`
- `status`
- `reset`

Example: `/hgos @s unlock all`, then `/hgos @s transform true`.

## Architecture

The server owns mastery, cooldowns, spells, held targets, projection navigation and aggression, weapon hit timing, bleeding, temporal fields, sanctum plots and transformation state. Client presentation covers layered Player Animator gestures, composited player skins, cloth simulation, authored meshes, illusory architecture geometry, additive particles and a dedicated post-processing chain.

**Cloak.** The cape is solved in world space, not in model space. Row zero of a 16×9 Verlet grid is written directly to the shoulder line every tick, so the cloak is attached by construction; every other row is a particle that lags behind. Trailing while running, lift on a fall, the sideways throw of a sharp turn and settling on landing come out of that lag rather than from a wave function. It collides against a capsule around the wearer and against the ground, carries itself through teleports rather than snapping taut, and is drawn in world space so it never inherits the mirrored model transform. Nothing about it is networked.

**Weapons.** Meshes are authored in Minecraft item-model units with the grip at the origin and the blade running up +Y. The item renderer hands a custom renderer the corner of the item's unit cube — the same space a vanilla sprite occupies — so correct hand alignment only requires putting the grip where a vanilla hilt sits and laying the blade along the diagonal that the inherited `item/handheld` transforms are built around. Off-hand twins apply the same placement rotated a half turn for a reverse grip.

**Telekinesis.** Targets ride a damped spring toward a smoothed aim point. Held players receive motion packets and are only repositioned when they have genuinely escaped, so ordinary struggling stays smooth instead of rubber-banding.

**Time.** Fields query bounded local volumes, cap their entity count and retain independent ownership. World tick rate never changes. The world, inventories, blocks and other players' health are never rewound. Temporal history is limited to 50 position/rotation/health samples per player. Players receive at most three seconds of suspension followed by a five-second protection window. Creative and spectator players are exempt.

**Sanctum.** `hexgodofstories:pocket` is a void dimension defined by datapack. Plots are allocated once per player and remembered in that dimension's saved data; the platform around the arrival point is laid immediately and the remainder is built across the following ticks, budgeted per tick. The way home is stored outside the transient state block so a dimension change cannot erase it.

Meshes, textures, particle sprites, animation keyframes and original synthesised audio are reproducible through `python tools/generate_assets.py` (Pillow and ffmpeg required only for regenerating assets). No audio is sampled from film or game sources.

## Validation status

Build and validation status is recorded in `docs/VALIDATION.md`. A successful Java build does not verify in-game appearance, shader compatibility, multiplayer illusion believability or model alignment. This project must not be described as having passed those checks unless a recorded game test actually establishes them.

## 0.2.1 — World tree and mantle fixes

- The cape seam follows the rendered torso, including Player Animator, crouching and body rotation. The longer hem drags on collision surfaces.
- A solid black forehead band fits around the Minecraft head and joins both horns.
- Court of Lies hides the caster completely; projections roam in separate sectors and stop stale paths instead of converging on the caster.
- Fracture has an eight-second cooldown. Each private realm is an open floating island beneath an emerald galaxy, with a large luminous world tree and a blackstone/gold throne at its heart.
- Existing sanctums upgrade when their owner enters. Generated walls and matching original floors are replaced; other placed blocks are retained. Construction is budgeted across ticks and its progress survives restarts.
- Crouch for three seconds on the green/gold arrival sigil to return without spending energy. Fracture also opens the normal return portal.

Client appearance and movement still require in-game verification; compilation alone cannot establish those results.

### 0.3.1 controls and realm update

Fracture: tap the primary key for a doorway, or hold for 0.6 seconds to gather all eligible entities within five blocks of the portal. After a brief inward pull, the group crosses and the portal closes. The same hold works inside the pocket realm; returning costs no energy and ignores entry cooldown. Visitors arrive in the caster's realm and keep their own way home.

While transformed, press **J** to toggle Cosmic Flight, in any dimension. **Space** rises and **crouch** descends; vanilla double-jump flight controls also work. Change the binding in Minecraft's Controls menu. A green spatial nebula and star filaments surround flying players. The mantle is the whole of the permission: nothing else grants flight, so an untransformed player is on foot everywhere, the Warping realms included.

The HUD shows eight named ability cards with recovery/ready status, selection and energy. Hold the select key and scroll to switch. Existing realms receive the larger island, organic tree/roots and rebuilt throne through a resumable upgrade; no world reset is needed.

### 0.3.2 HUD correction

The HUD is now a compact bottom-left readout. It describes the selected ability's primary and alternate actions, readiness/recovery, energy cost and remaining energy. Hold V and scroll to inspect another bound ability; release to select it. The large eight-card overlay is removed. Decorative effect rings and orbiting wire strands are removed, including those around Cosmic Flight; its nebula clouds remain.
