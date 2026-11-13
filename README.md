# Titans of Paradis — Fabric mod for Minecraft 26.3

Requires: Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API 0.160.5+26.3, Java 25.

## Build
```
gradle wrapper --gradle-version 9.6.0     # once
./gradlew build                            # jar in build/libs/
./gradlew runClient                        # test in dev
```
26.x is unobfuscated, so there is no `mappings` line and dependencies use `implementation` (not `modImplementation`).

## Getting started (creative tab "Titans of Paradis")
1. Drink an **Attack Titan Serum** or **War Hammer Titan Serum** (one power per player, kept after death).
2. Hold a **Titan Spike** and right-click: lightning, steam, and a smooth 2-second growth to 10x / 12x size.

| Titan Spike action | Result |
|---|---|
| right-click (human) | transform |
| right-click (Titan) | Attack: **Roar** shockwave. War Hammer: **forge** the selected crystal construct |
| sprint + right-click (War Hammer) | cycle construct |
| sneak + right-click | revert (exhausted for a while, spike on cooldown) |

**Attack Titan:** huge punches with splash damage and terrain shattering, fast regeneration, empty-hand right-click to **grab** a mob, **sneak to throw** it. 
**War Hammer Titan:** Giant Sword, Spear, War Hammer, Bow (14-arrow volley), Crossbow (piercing bolt), Shield, Spike Field, Wall, Pillar. Crystal rises layer by layer and shatters after 45 s.
Titan form lasts 5 minutes and is destructive: it breaks blocks up to hardness 6 (needs `mobGriefing` on).

## ODM gear
Right-click fires a hook (max two). Missing = gas dash. Sneak+right-click releases. Sprint while hooked reels in and thrusts. The durability bar is your gas — refill with **Gas Canisters**. Hit speed multiplies damage against Titans.

## Titans & bosses
Titans wander toward players (random waves, max 3 nearby). Hit the **nape** (from behind, above 60% height) for 6x damage and to stop their regeneration. Thunder Spears deal +90 damage to Titans in a 7-block blast. The **Rogue Titan** (spawn egg) has 1500 HP, ground-slam shockwaves, summons Titans at 50% HP and drops a serum + Thunder Spears.

## Art
All textures are original procedurally generated placeholders (generic humanoid giant, abstract icons).
Drop in your own `titan.png` (64x64 vanilla humanoid UV) at `assets/aot/textures/entity/` or swap the renderer for a GeckoLib model.
