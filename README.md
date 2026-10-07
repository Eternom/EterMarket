# EterMarket

Le marché du réseau : **PNJ boutiques**, **stock commun** et **guilde des métiers** (quêtes de livraison quotidiennes,
le vrai revenu du serveur). À venir : l'**hôtel des ventes** (entre joueurs, sur un PNJ).
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

## Guilde des métiers (`module/job`)

**Un métier par joueur** (`etermarket_job_members`) parmi `jobs.list` (Mineur, Bûcheron, Fermier, Chasseur, Pêcheur).
Le premier est gratuit ; en changer coûte `change-cost` (2 000) et n'est possible qu'une fois par
`change-cooldown-days` (7). On le choisit au PNJ de référence du métier (`/market create <pnj> job <métier>`).

**Quêtes du jour** (`etermarket_job_quests`) : à minuit (`jobs.time-zone`), `quests-per-day` (3) quêtes tirées au hasard
dans le **répertoire** du métier (`etermarket_job_catalog`, recopiées : modifier le répertoire ne change pas les quêtes
déjà tirées), puis une **quête bonus** (récompense × `bonus-multiplier`) quand elles sont faites. Uniquement des
**livraisons** : la validation vérifie que le joueur a les objets (simples, sans nom ni enchantement) et les lui prend.

**Valider** (`JobService#deliver`) : objets retirés → la base marque la quête faite **une seule fois**
(`UPDATE ... WHERE done = FALSE`) → objets versés dans le **stock commun** → récompense par Vault. Quête déjà faite
(double clic, autre serveur) : objets rendus.

**Le PNJ de métier** ouvre la guilde (rejoindre) ou les quêtes du jour ; un onglet mène à sa **boutique** (objets utiles
au métier, même éditeur que les boutiques). **Répertoire de départ** (`Jobs#defaults`) posé au premier démarrage,
modifiable dans l'éditeur (Maj + clic droit, bouton « Répertoire des quêtes ») : 150 à 250 Heloks par quête.

**Contrôle d'arbitrage** : les deux éditeurs affichent en rouge un objet vendu en boutique moins cher **à l'unité** que
ce que sa livraison rapporte (on achèterait pour livrer). Repères : « Repères économiques » d'EterEconomy.

## Stock commun (`module/stock`)

**Un seul stock pour tout** (toutes les boutiques, tous les serveurs), par matière (`etermarket_stock`). Les livraisons
des quêtes l'alimenteront toujours ; une boutique n'y puise que si elle est réglée « vente sur stock » (sinon :
illimitée). Seuls les objets simples (sans nom ni enchantement) passent par le stock. L'hôtel des ventes n'y touchera
jamais (il est entre joueurs).

## Commandes et permissions

| Commande | Rôle |
|---|---|
| `/market create <pnj>` | Crée une boutique (une fois pour tout le réseau) |
| `/market create <pnj> job <métier>` | Crée le PNJ de référence d'un métier |
| `/market place <pnj>` | Le place là où tu es |
| `/market remove` | Retire l'emplacement le plus proche (5 blocs) |
| `/market edit <pnj>` | Éditeur de la boutique |
| `/market delete <pnj> confirm` | Supprime le PNJ partout, avec ses emplacements et sa boutique |
| `/market list` · `/market reload` | Liste · rechargement |

`/market` : `etermarket.admin` (op). Éditeur au clic : `etermarket.edit` (dans `etermarket.admin`).
Pas de `/shop` : les joueurs vont voir les PNJ.
