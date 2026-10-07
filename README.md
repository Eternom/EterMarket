# EterMarket

Le marché du réseau. Étape 1 (cette version) : **PNJ boutiques** et **stock commun**. Étapes suivantes : la **guilde des
métiers** (quêtes de livraison quotidiennes, seul vrai revenu) et l'**hôtel des ventes** (entre joueurs, sur un PNJ).
Conception complète : voir la mémoire de projet et les « Repères économiques » d'EterEconomy.
Document développeur, à tenir à jour avec le code.

## Prérequis

- **EterLib 1.5.2+** (`depend`) : base, Redis, langues, menus, Dialogs.
- **Vault + EterEconomy** pour payer (sinon : « économie indisponible »).
- Client 1.21.6+ pour les Dialogs (quantité, éditeur).

## PNJ (`module/npc`)

**Définis une fois, placés partout.** `etermarket_npcs` : la définition (identifiant, nom MiniMessage, skin, rôle,
vente sur stock), commune à tout le réseau. `etermarket_placements` : chaque emplacement (serveur, monde, position).
Un même PNJ peut être placé autant de fois qu'on veut, sur n'importe quel serveur.

**Mannequins natifs** (`NpcSpawner`), pas de Citizens : skin par pseudo (résolu par le jeu) ou texture précise
(valeur + signature, ex : MineSkin, prioritaire) ; invulnérables, immobiles, silencieux. **Jamais enregistrés dans le
monde** (non persistants) : ils apparaissent au chargement du chunk de leur emplacement et disparaissent avec lui,
donc jamais de doublon après un redémarrage. Chaque Mannequin porte l'identifiant de son PNJ (données persistantes de
l'entité) pour reconnaître un clic.

**Synchronisation** : une définition modifiée sur un serveur (éditeur, création, suppression) est rechargée par les
autres via Redis (canal `etermarket`, message `reload:<serveur>`, ignoré par l'émetteur). Sans Redis :
`/market reload` sur chaque serveur.

## Boutiques (`module/shop`)

Chaque PNJ vend **sa propre liste** (`etermarket_shop_items` : l'objet exact sérialisé, avec la quantité d'un lot, et
le prix du lot). **Les boutiques ne rachètent rien** : l'argent entre par les quêtes des métiers.

**Achat** (`ShopService`) : place dans l'inventaire vérifiée sur une copie (sinon refus) → stock commun retiré si la
boutique vend sur stock (atomique) → paiement par Vault (atomique) ; paiement refusé = stock rendu. Les objets ne sont
donnés qu'à la fin. Clic gauche : un lot ; clic droit : quantité choisie dans un Dialog (1 à 64 lots).

**Éditeur** (`ShopEditorMenu`, cadre rouge ; `/market edit <pnj>` ou Maj + clic droit, `etermarket.edit`) : ajouter
l'objet tenu (la quantité tenue = un lot) avec son prix (Dialog), changer un prix, retirer (confirmation), vente sur
stock oui/non, nom et skin du PNJ.

## Stock commun (`module/stock`)

**Un seul stock pour tout** (toutes les boutiques, tous les serveurs), par matière (`etermarket_stock`). Les livraisons
des quêtes l'alimenteront toujours ; une boutique n'y puise que si elle est réglée « vente sur stock » (sinon :
illimitée). Seuls les objets simples (sans nom ni enchantement) passent par le stock. L'hôtel des ventes n'y touchera
jamais (il est entre joueurs).

## Commandes et permissions

| Commande | Rôle |
|---|---|
| `/market create <pnj>` | Crée un PNJ (une fois pour tout le réseau) |
| `/market place <pnj>` | Le place là où tu es |
| `/market remove` | Retire l'emplacement le plus proche (5 blocs) |
| `/market edit <pnj>` | Éditeur de la boutique |
| `/market delete <pnj> confirm` | Supprime le PNJ partout, avec ses emplacements et sa boutique |
| `/market list` · `/market reload` | Liste · rechargement |

`/market` : `etermarket.admin` (op). Éditeur au clic : `etermarket.edit` (dans `etermarket.admin`).
Pas de `/shop` : les joueurs vont voir les PNJ.
