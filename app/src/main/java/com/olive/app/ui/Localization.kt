package com.olive.app.ui

import androidx.compose.runtime.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

val LocalOliveLanguage = staticCompositionLocalOf { "English" }
// Use this instead of plain Text when copy should flip between English and French.
@Composable fun LocalizedText(text: String, modifier: Modifier=Modifier, color: Color=Color.Unspecified,
    fontSize: TextUnit=TextUnit.Unspecified, fontWeight: FontWeight?=null, fontFamily: FontFamily?=null,
    letterSpacing: TextUnit=TextUnit.Unspecified, textAlign: TextAlign?=null, lineHeight: TextUnit=TextUnit.Unspecified,
    overflow: TextOverflow=TextOverflow.Clip, maxLines: Int=Int.MAX_VALUE, style: TextStyle=LocalTextStyle.current) {
    MaterialText(translate(text,LocalOliveLanguage.current),modifier=modifier,color=color,fontSize=fontSize,fontWeight=fontWeight,fontFamily=fontFamily,
        letterSpacing=letterSpacing,textAlign=textAlign,lineHeight=lineHeight,overflow=overflow,maxLines=maxLines,style=style)
}
// Add English or French text here; add new languages to both UI pickers before using them.
fun translate(text: String, language: String): String {
    if(language!="French") return text
    french[text]?.let { return it }
    val patterns=listOf(
        Regex("Incorrect PIN. ([0-9]+) attempts remaining\\.") to "NIP incorrect. %s tentatives restantes.",
        Regex("PIN attempt history · ([0-9]+)") to "Historique des tentatives de NIP · %s",
        Regex("Future feature ([0-9]+)") to "Fonction future %s",
        Regex("([0-9]+) dishes") to "%s plats",
        Regex("Add another · ([0-9]+) in cart") to "Ajouter encore · %s dans le panier",
        Regex("Subtotal: (.+)") to "Sous-total : %s",
        Regex("News near (.+)") to "Actualités près de %s",
        Regex("No activity. Returning to your cover in ([0-9]+) seconds\\.") to "Aucune activité. Retour à l’application de couverture dans %s secondes."
    )
    for((pattern,format) in patterns) pattern.matchEntire(text)?.let { return format.format(it.groupValues[1]) }
    return text
}
private val french = """
Use shipping address|Utiliser l’adresse de livraison
Enter a complete shipping address.|Saisissez une adresse de livraison complète.
Recipient name is required.|Le nom du destinataire est obligatoire.
Name on card is required.|Le nom figurant sur la carte est obligatoire.
Enter a valid card number with no more than 16 digits.|Saisissez un numéro de carte valide de 16 chiffres maximum.
Enter a valid expiry as MM/YY.|Saisissez une date d’expiration valide au format MM/AA.
Enter a 3 or 4 digit security code.|Saisissez un code de sécurité de 3 ou 4 chiffres.
Billing address is required.|L’adresse de facturation est obligatoire.
Processing payment…|Traitement du paiement…
Local|Local
Global|Monde
Date of birth (required)|Date de naissance (obligatoire)
Year|Année
Month|Mois
Day|Jour
The app stays open. Its icon and name update after you return to the Home screen.|L’application reste ouverte. Son icône et son nom changent lorsque vous revenez à l’écran d’accueil.
Enter your PIN.|Saisissez votre NIP.
Olive and Daily Digest ignore spaces and letter case in your trigger. Other searches keep their spaces. Simple Calculator uses your PIN and two equals taps.|Olive et Daily Digest ignorent les espaces et la casse dans le déclencheur. Les autres recherches conservent leurs espaces. Simple Calculator utilise votre NIP et deux pressions sur égal.
Locking soon|Verrouillage imminent
Stay here|Rester ici
Read full story|Lire l’article complet
Live news is unavailable. Check your connection and try again.|Les actualités en direct sont indisponibles. Vérifiez votre connexion et réessayez.
Privacy events|Événements de confidentialité
No capture events reported by Android.|Aucun événement de capture signalé par Android.
Screen recording detected|Enregistrement d’écran détecté
Screenshot detected|Capture d’écran détectée
Account activity|Activité du compte
Settings, safe entry and attempted food checkouts on this device.|Paramètres, accès sécurisé et tentatives de commande sur cet appareil.
Settings history|Historique des paramètres
Trigger history|Historique des accès
Checkout attempts|Tentatives de commande
No settings activity yet.|Aucune activité dans les paramètres.
No trigger activity yet.|Aucune activité d’accès.
No checkout attempts yet.|Aucune tentative de commande.
Enter your city.|Saisissez votre ville.
Order history|Historique des commandes
Attempted checkouts on this device.|Tentatives de commande sur cet appareil.
Order history could not be saved on this device.|L’historique de commande n’a pas pu être enregistré sur cet appareil.
PIN attempts|Tentatives de NIP
No PIN attempt|Aucune tentative de NIP
Accepted|Accepté
Empty|Vide
Hidden|Masqué
Changed|Modifié
Not set|Non défini
Set|Défini
Location|Emplacement
Safe entry|Accès sécurisé
City is required. The rest of your delivery address is optional and can be added later.|La ville est obligatoire. Le reste de l’adresse de livraison est facultatif et peut être ajouté plus tard.
Full name, a valid date of birth, language and city are required.|Le nom complet, une date de naissance valide, la langue et la ville sont obligatoires.
Street address (optional)|Adresse municipale (facultatif)
Province / state (optional)|Province / État (facultatif)
Postal / ZIP code (optional)|Code postal (facultatif)
Country (optional)|Pays (facultatif)
Onboarding|Configuration initiale
Olive search|Recherche Olive
Daily Digest search|Recherche Daily Digest
Simple Calculator|Simple Calculator
Cover|Application de couverture
City|Ville
Street address|Adresse municipale
Apartment / unit|Appartement / unité
Province / state|Province / État
Postal / ZIP|Code postal
Country|Pays
Language|Langue
Created profile|Profil créé
Saved profile settings|Paramètres du profil enregistrés
Saved account settings|Paramètres du compte enregistrés
Saved delivery address|Adresse de livraison enregistrée
Changed cover app|Application de couverture modifiée
Changed trigger word|Mot déclencheur modifié
Saved safe contacts|Contacts de confiance enregistrés
Signed out on this device|Déconnexion sur cet appareil
Changed account password|Mot de passe du compte modifié
Changed device PIN|NIP de l’appareil modifié
Password|Mot de passe
Device PIN|NIP de l’appareil
Safe contacts|Contacts de confiance
Opened account page|Page du compte ouverte
Opened settings page|Page des paramètres ouverte
Opened profile page|Page du profil ouverte
Opened contacts page|Page des contacts ouverte
Opened delivery address editor|Éditeur d’adresse de livraison ouvert
Opened contact editor|Éditeur de contact ouvert
Selected remove contact|Suppression de contact sélectionnée
Selected cover app|Application de couverture sélectionnée
Selected language|Langue sélectionnée
Pressed save delivery address|Enregistrement de l’adresse de livraison demandé
Pressed save profile settings|Enregistrement du profil demandé
Pressed save account settings|Enregistrement des paramètres du compte demandé
Pressed change trigger word|Modification du mot déclencheur demandée
Pressed save safe contacts|Enregistrement des contacts demandé
Pressed sync changes|Synchronisation demandée
Pressed change account password|Modification du mot de passe demandée
Pressed change device PIN|Modification du NIP demandée
Focused full name|Champ du nom complet sélectionné
Focused affected names|Champ des personnes concernées sélectionné
Focused abuser name|Champ du nom de l’auteur des violences sélectionné
Focused date of birth|Champ de la date de naissance sélectionné
Focused situation|Champ de la situation sélectionné
Focused contact name|Champ du nom du contact sélectionné
Focused contact details|Champ des coordonnées du contact sélectionné
Focused current password|Champ du mot de passe actuel sélectionné
Focused new password|Champ du nouveau mot de passe sélectionné
Focused password confirmation|Champ de confirmation du mot de passe sélectionné
Focused current PIN|Champ du NIP actuel sélectionné
Focused new PIN|Champ du nouveau NIP sélectionné
Focused PIN confirmation|Champ de confirmation du NIP sélectionné
Focused trigger word|Champ du mot déclencheur sélectionné
Focused trigger-change PIN|Champ du NIP du mot déclencheur sélectionné
Focused address: Street address|Champ de l’adresse municipale sélectionné
Focused address: Apartment / unit|Champ de l’appartement sélectionné
Focused address: City|Champ de la ville sélectionné
Focused address: Province / state|Champ de la province ou de l’État sélectionné
Focused address: Postal / ZIP code|Champ du code postal sélectionné
Focused address: Country|Champ du pays sélectionné
Focused address: Google address selection|Suggestion d’adresse Google sélectionnée
Trigger word|Mot déclencheur
Cover app|Application de couverture
English|Anglais
French|Français
BOWLS|BOLS
BURGERS|BURGERS
SUSHI|SUSHIS
Discover|Découvrir
Saved|Favoris
Cart|Panier
Account|Compte
All|Tout
Bowls|Bols
Pizza|Pizza
Burgers|Burgers
Sushi|Sushis
DELIVER TO|LIVRER À
Choose your area|Choisir votre adresse
A LITTLE FRESHNESS, EVERY DAY|UN PEU DE FRAÎCHEUR, CHAQUE JOUR
Good food.\nGood mood.|Bien manger.\nSe sentir bien.
Find your next feel-good favourite.|Découvrez votre prochain plat préféré.
Clear search|Effacer la recherche
Put ad here|Placez votre publicité ici
Search results|Résultats de recherche
Made for your cravings|Pour toutes vos envies
Nothing on the menu yet|Aucun plat trouvé
Try a different search or choose another category.|Essayez une autre recherche ou catégorie.
Back to menu|Retour au menu
Back to menu|Retour au menu
Add to cart|Ajouter au panier
Saved to favourites|Enregistré dans les favoris
Save for later|Garder pour plus tard
Your favourites|Vos favoris
Good finds, kept close.|Vos découvertes, à portée de main.
A place for your favourites|Un espace pour vos favoris
Tap the heart on any dish to save it here.|Touchez le cœur d’un plat pour l’enregistrer ici.
Your cart|Votre panier
A few good things, all together.|Toutes vos envies au même endroit.
Something delicious belongs here|Une gourmandise vous attend
Explore the menu and add a dish to get started.|Explorez le menu et ajoutez un plat pour commencer.
Explore the menu|Explorer le menu
Subtotal|Sous-total
Review your delivery and payment details at checkout.|Vérifiez les détails de livraison et de paiement à la caisse.
Continue to checkout|Passer à la caisse
Make yourself at home|Bienvenue chez vous
A little space of your own.|Un petit espace rien qu’à vous.
Welcome to Olive|Bienvenue sur Olive
Browse the menu and collect your favourites.|Explorez le menu et enregistrez vos favoris.
Set up account|Configurer un compte
Your Olive account|Votre compte Olive
No delivery address saved|Aucune adresse de livraison enregistrée
Change delivery address|Modifier l’adresse de livraison
Account settings|Paramètres du compte
App language|Langue de l’application
Save account settings|Enregistrer les paramètres du compte
Enter your full name and preferred language.|Saisissez votre nom complet et votre langue préférée.
Unable to save account settings.|Impossible d’enregistrer les paramètres du compte.
Account settings saved.|Paramètres du compte enregistrés.
Find a new favourite|Trouvez un nouveau favori
Fresh bowls, stone-baked pizza and little everyday cravings. Tap the heart on a dish to keep it close.|Bols frais, pizzas au four et petits plaisirs du quotidien. Touchez le cœur d’un plat pour le retrouver facilement.
Quick exit|Sortie rapide
Unable to open local storage|Impossible d’ouvrir le stockage local
Access remains locked. Your existing data has not been replaced.|L’accès reste verrouillé. Vos données existantes n’ont pas été remplacées.
Retry|Réessayer
Local test mode: accounts stay on this installation. Firebase is not connected. Use fictional information while testing.|Mode de test local : les comptes restent sur cet appareil. Firebase n’est pas connecté. Utilisez des informations fictives pour les essais.
Choose how you’d like to get started.|Choisissez comment commencer.
I’m a new user|Je suis un nouvel utilisateur
I’m a returning user|J’ai déjà un compte
You can use Quick exit at any time to open the food menu. If setup is incomplete, return through Account.|Utilisez Sortie rapide à tout moment pour ouvrir le menu. Si la configuration est incomplète, revenez par Compte.
Create your account|Créer votre compte
Welcome back|Heureux de vous revoir
Use these credentials when setting up another device.|Utilisez ces identifiants pour configurer un autre appareil.
Sign in with your existing username and password. Next, create a PIN for this device.|Connectez-vous avec votre nom d’utilisateur et votre mot de passe, puis créez un NIP pour cet appareil.
Username|Nom d’utilisateur
Password|Mot de passe
Confirm password|Confirmer le mot de passe
Passwords use 8–24 characters and are case-sensitive. Usernames are unique, ignore letter case, and use letters, numbers and underscores. No email or phone number needed.|Les mots de passe comportent de 8 à 24 caractères et distinguent les majuscules. Les noms d’utilisateur sont uniques, sans distinction de casse, et utilisent lettres, chiffres et traits de soulignement. Aucun courriel ni numéro de téléphone requis.
Keep your credentials somewhere safe. You can change your password later in safe Settings.|Conservez vos identifiants en lieu sûr. Vous pourrez changer votre mot de passe dans les paramètres sécurisés.
The passwords do not match.|Les mots de passe ne correspondent pas.
Create account|Créer le compte
Sign in|Se connecter
Your profile|Votre profil
Full name, date of birth, language, location and a trigger word are required.|Le nom complet, la date de naissance, la langue, l’emplacement et un mot déclencheur sont obligatoires.
Full name, date of birth, language, city and a trigger word are required.|Le nom complet, la date de naissance, la langue, la ville et un mot déclencheur sont obligatoires.
Full name (required)|Nom complet (obligatoire)
Names of people experiencing abuse (optional)|Noms des personnes victimes de violence (facultatif)
Abuser's name (optional)|Nom de l’auteur des violences (facultatif)
Date of birth · YYYY-MM-DD (required)|Date de naissance · AAAA-MM-JJ (obligatoire)
Language (required)|Langue (obligatoire)
Brief description of your situation (optional, recommended)|Brève description de votre situation (facultatif, recommandé)
Location (required)|Emplacement (obligatoire)
Powered by Google|Fourni par Google
Address suggestions are unavailable. Continue entering your address.|Les suggestions d’adresse sont indisponibles. Continuez à saisir votre adresse.
That address could not be filled automatically. Continue entering your address.|Cette adresse n’a pas pu être remplie automatiquement. Continuez à saisir votre adresse.
Trigger word or phrase|Mot ou phrase déclencheur
Enter this exact word or phrase in the food search to open the PIN prompt. Matching ignores case and outer spaces. Choose something you won’t search for by accident.|Saisissez ce mot ou cette phrase dans la recherche de plats pour ouvrir la demande de NIP. Les majuscules et espaces extérieurs sont ignorés. Choisissez une expression que vous ne chercherez pas par hasard.
Safe contact · optional|Contact de confiance · facultatif
Contact name|Nom du contact
Contact phone or email|Téléphone ou courriel du contact
Contacts can be added and edited later. Olive never contacts anyone automatically.|Vous pourrez ajouter et modifier les contacts plus tard. Olive ne contacte jamais personne automatiquement.
Enter your full name.|Saisissez votre nom complet.
Use a valid date of birth in YYYY-MM-DD format.|Saisissez une date de naissance valide au format AAAA-MM-JJ.
Enter your preferred language.|Choisissez votre langue.
Choose or enter a complete address for your location.|Choisissez ou saisissez une adresse complète pour votre emplacement.
Choose a trigger word or phrase of 3–64 characters.|Choisissez un mot ou une phrase déclencheur de 3 à 64 caractères.
Enter both contact fields or leave both empty.|Remplissez les deux champs du contact ou laissez-les vides.
Save profile and continue|Enregistrer le profil et continuer
Secure this device|Sécuriser cet appareil
Create a 4–6 digit PIN. Your account password signs you in on another device; this PIN opens the safe area here.|Créez un NIP de 4 à 6 chiffres. Le mot de passe sert à vous connecter sur un autre appareil; ce NIP ouvre l’espace sécurisé ici.
4–6 digit PIN|NIP de 4 à 6 chiffres
Confirm PIN|Confirmer le NIP
Five incorrect attempts return you to the food menu. You can try again immediately. Failed attempts are recorded on this device.|Après cinq tentatives incorrectes, vous revenez au menu. Vous pouvez réessayer immédiatement. Les tentatives échouées sont enregistrées sur cet appareil.
Finish setup|Terminer la configuration
Enter PIN|Saisir le NIP
Enter the 4–6 digit PIN for this device.|Saisissez le NIP de 4 à 6 chiffres de cet appareil.
PIN|NIP
Unlock|Déverrouiller
Your safe space|Votre espace sécurisé
Use Quick exit from any page to lock this area and return to the food menu.|Utilisez Sortie rapide sur n’importe quelle page pour verrouiller cet espace et revenir au menu.
My profile|Mon profil
Settings|Paramètres
Safe contacts|Contacts de confiance
Profile changes are saved on this device and awaiting cloud sync.|Les modifications du profil sont enregistrées sur cet appareil et attendent la synchronisation.
Sync changes|Synchroniser
Leaving the app locks this area. The food cover does not make the app’s purpose impossible to discover.|Quitter l’application verrouille cet espace. L’interface de livraison ne garantit pas que la fonction de l’application reste cachée.
Sign out on this device|Se déconnecter sur cet appareil
Future feature 1|Fonction future 1
Future feature 2|Fonction future 2
Future feature 3|Fonction future 3
Future feature 4|Fonction future 4
Private features|Fonctions privées
All activity|Toute l’activité
Search activity between optional local date and time values.|Recherchez l’activité entre des dates et heures locales facultatives.
See more|Voir plus
Notes & incidents|Notes et incidents
Notes & incident documentation|Notes et documentation d’incidents
Edit private entry|Modifier l’entrée privée
Write private notes or document incidents. Entries are shown only inside Olive Branch and sync to your account.|Écrivez des notes privées ou documentez des incidents. Les entrées sont affichées uniquement dans Olive Branch et se synchronisent avec votre compte.
Note|Note
Incident|Incident
Title (optional)|Titre (facultatif)
Private details|Détails privés
Save private entry|Enregistrer l’entrée privée
Update private entry|Mettre à jour l’entrée privée
Cancel edit|Annuler la modification
Saved entries|Entrées enregistrées
No private entries yet.|Aucune entrée privée pour le moment.
Write something before saving.|Écrivez quelque chose avant d’enregistrer.
Use up to 120 title characters and 10,000 detail characters.|Utilisez au maximum 120 caractères pour le titre et 10 000 caractères pour les détails.
Unable to save this private entry. Please retry.|Impossible d’enregistrer cette entrée privée. Veuillez réessayer.
Saved privately on this device.|Enregistré de façon privée sur cet appareil.
Saved privately. Syncing to your account.|Enregistré de façon privée. Synchronisation avec votre compte.
Your saved account information.|Les renseignements de votre compte.
Full name|Nom complet
People experiencing abuse|Personnes victimes de violence
Abuser's name|Nom de l’auteur des violences
Date of birth|Date de naissance
Preferred language|Langue préférée
Situation|Situation
Region / city / state|Région / ville / province
Not provided|Non indiqué
PIN attempt history|Historique des tentatives de NIP
Incorrect PIN attempts on this installation. Times use this device’s clock and time zone; entered PINs are never recorded.|Tentatives incorrectes sur cet appareil. Les heures utilisent son horloge et son fuseau horaire; les NIP saisis ne sont jamais enregistrés.
No failed attempts recorded.|Aucune tentative échouée enregistrée.
Incorrect PIN|NIP incorrect
Safe home|Accueil sécurisé
Save trusted people here. Olive does not send messages or make calls.|Enregistrez vos contacts de confiance ici. Olive n’envoie pas de messages et ne passe pas d’appels.
Edit|Modifier
Remove|Supprimer
No contacts yet. Adding one is optional.|Aucun contact pour le moment. L’ajout est facultatif.
Add a contact|Ajouter un contact
Edit contact|Modifier le contact
Phone or email|Téléphone ou courriel
Enter a name and a phone number or email.|Saisissez un nom et un téléphone ou courriel.
You can save up to 25 contacts.|Vous pouvez enregistrer jusqu’à 25 contacts.
Save contact|Enregistrer le contact
Cancel edit|Annuler la modification
Update your profile and language at any time.|Modifiez votre profil et votre langue à tout moment.
Save settings|Enregistrer les paramètres
Change account password|Changer le mot de passe du compte
Your username cannot be changed.|Votre nom d’utilisateur ne peut pas être modifié.
Current password|Mot de passe actuel
New password|Nouveau mot de passe
Confirm new password|Confirmer le nouveau mot de passe
Change password|Changer le mot de passe
Change device PIN|Changer le NIP de l’appareil
Current device PIN|NIP actuel de l’appareil
New device PIN|Nouveau NIP de l’appareil
Confirm new device PIN|Confirmer le nouveau NIP de l’appareil
Change trigger word|Changer le mot déclencheur
Enter your current device PIN to change the trigger word. Incorrect attempts count toward the five-attempt limit.|Saisissez votre NIP actuel pour changer le mot déclencheur. Les tentatives incorrectes comptent dans la limite de cinq.
New trigger word or phrase|Nouveau mot ou phrase déclencheur
Current PIN|NIP actuel
Street address (required)|Adresse municipale (obligatoire)
Apartment / unit (optional)|Appartement / unité (facultatif)
City (required)|Ville (obligatoire)
Province / state (required)|Province / État (obligatoire)
Postal / ZIP code (required)|Code postal (obligatoire)
Country (required)|Pays (obligatoire)
Delivery address|Adresse de livraison
Where should we deliver your food?|Où devons-nous livrer votre repas?
Save delivery address|Enregistrer l’adresse de livraison
Done|Terminé
Payment declined|Paiement refusé
We couldn’t complete your payment. No charge was made and no order was placed.|Votre paiement n’a pas abouti. Aucun montant n’a été débité et aucune commande n’a été passée.
Return to menu|Retour au menu
Checkout|Caisse
Delivery details|Détails de livraison
Recipient name|Nom du destinataire
Delivery instructions (optional)|Instructions de livraison (facultatif)
Payment details|Détails du paiement
Name on card|Nom sur la carte
Card number|Numéro de carte
Expiry · MM/YY|Expiration · MM/AA
Security code|Code de sécurité
Billing address|Adresse de facturation
Place order|Passer la commande
Use 4–24 letters, numbers or underscores for your username.|Utilisez de 4 à 24 lettres, chiffres ou traits de soulignement pour votre nom d’utilisateur.
Use a password of 8–24 characters.|Utilisez un mot de passe de 8 à 24 caractères.
Enter your password.|Saisissez votre mot de passe.
Unable to sign in. Check your credentials and connection, then try again.|Connexion impossible. Vérifiez vos identifiants et votre connexion, puis réessayez.
Full name, a valid date of birth, language and location are required.|Le nom complet, une date de naissance valide, la langue et l’emplacement sont obligatoires.
Your account exists, but the profile was not saved. Retry here, or sign in again to finish setup.|Votre compte existe, mais le profil n’a pas été enregistré. Réessayez ici ou reconnectez-vous pour terminer.
Enter the same 4–6 digit PIN in both fields.|Saisissez le même NIP de 4 à 6 chiffres dans les deux champs.
Unable to save this device's PIN. Please retry.|Impossible d’enregistrer le NIP de cet appareil. Réessayez.
Enter your 4–6 digit PIN.|Saisissez votre NIP de 4 à 6 chiffres.
Unable to check the PIN or record this attempt. Access remains locked.|Impossible de vérifier le NIP ou d’enregistrer la tentative. L’accès reste verrouillé.
Enter a complete delivery address.|Saisissez une adresse de livraison complète.
Unable to save the address. Please retry.|Impossible d’enregistrer l’adresse. Réessayez.
Address saved.|Adresse enregistrée.
Unable to save profile changes.|Impossible d’enregistrer les modifications du profil.
Changes saved.|Modifications enregistrées.
Enter a trigger of 3–64 characters and your 4–6 digit PIN.|Saisissez un déclencheur de 3 à 64 caractères et votre NIP de 4 à 6 chiffres.
Unable to change the trigger. Please retry.|Impossible de changer le déclencheur. Réessayez.
Trigger word changed.|Mot déclencheur modifié.
Enter your current password.|Saisissez votre mot de passe actuel.
Use a new password of 8–24 characters.|Utilisez un nouveau mot de passe de 8 à 24 caractères.
The new passwords do not match.|Les nouveaux mots de passe ne correspondent pas.
Unable to change the password. Check your current password and connection.|Impossible de changer le mot de passe. Vérifiez votre mot de passe actuel et votre connexion.
Password changed.|Mot de passe modifié.
Enter 4–6 digits for both PINs.|Saisissez de 4 à 6 chiffres pour les deux NIP.
The new PINs do not match.|Les nouveaux NIP ne correspondent pas.
Choose your cover app|Choisissez votre application de couverture
Food Delivery App · Olive|Livraison de repas · Olive
Game · In development|Jeu · En développement
News App · Daily Digest|Actualités · Daily Digest
Calculator · Simple Calculator|Calculatrice · Simple Calculator
In development|En développement
Olive Branch|Olive Branch
In Olive or Daily Digest, enter this word or phrase in search. In Simple Calculator, enter your device PIN and tap equals twice.|Dans Olive ou Daily Digest, saisissez ce mot ou cette phrase dans la recherche. Dans Simple Calculator, saisissez le NIP de l’appareil et appuyez deux fois sur le signe égal.
Olive and Daily Digest use search for safe entry. Simple Calculator uses your device PIN followed by two equals taps.|Olive et Daily Digest utilisent la recherche pour l’accès sécurisé. Simple Calculator utilise le NIP de l’appareil suivi de deux pressions sur le signe égal.
Sign out?|Se déconnecter?
You will need your username and password to return on this device.|Vous aurez besoin de votre nom d’utilisateur et de votre mot de passe pour revenir sur cet appareil.
Cancel|Annuler
Five incorrect attempts return you to the cover app. You can try again immediately. Failed attempts are recorded on this device.|Cinq tentatives incorrectes vous ramènent à l’application de couverture. Vous pouvez réessayer immédiatement. Les tentatives échouées sont enregistrées sur cet appareil.
Leaving the app locks this area. A cover app does not make Olive Branch impossible to discover.|Quitter l’application verrouille cet espace. Une application de couverture ne rend pas Olive Branch impossible à découvrir.
Use Quick exit from any page to lock this area and return to your cover app.|Utilisez Sortie rapide depuis n’importe quelle page pour verrouiller cet espace et revenir à votre application de couverture.
Cover app|Application de couverture
Sign out|Se déconnecter
Today|Aujourd’hui
Search stories|Rechercher des articles
No saved stories yet.|Aucun article enregistré.
No stories found.|Aucun article trouvé.
Remove bookmark|Retirer le signet
Save article|Enregistrer l’article
History search|Rechercher dans l’historique
Close|Fermer
← Back|← Retour
Living|Vie quotidienne
Food|Cuisine
Home|Maison
Culture|Culture
A calmer start to the day|Un début de journée plus calme
Small routines that make busy mornings easier.|De petites habitudes pour simplifier les matins chargés.
Prepare one thing the night before, leave a little extra time, and choose a simple first task. A calm start is often built from small repeatable habits.|Préparez une chose la veille, prévoyez un peu plus de temps et choisissez une première tâche simple. Un début calme se construit souvent avec de petites habitudes répétées.
What makes a good weeknight meal?|Comment réussir un repas de semaine?
A few practical ideas for dinner planning.|Quelques idées pratiques pour planifier le souper.
Keep a few flexible staples on hand and build meals around what you already have. A short list of familiar options can make weeknights feel easier.|Gardez quelques aliments polyvalents sous la main et préparez les repas avec ce que vous avez déjà. Une courte liste de plats familiers peut simplifier les soirs de semaine.
Make a little room for reading|Faites une place à la lecture
Create a comfortable corner with what you have.|Créez un coin confortable avec ce que vous avez.
Good light, a comfortable seat, and a place to set down a cup can turn an ordinary corner into a useful reading spot.|Un bon éclairage, un siège confortable et un endroit où poser une tasse peuvent transformer un coin ordinaire en espace de lecture.
The pleasure of a local walk|Le plaisir d’une promenade près de chez soi
Notice something new on a familiar route.|Remarquez quelque chose de nouveau sur un trajet familier.
Try a different street, look for changing seasons, or stop at a nearby library. Exploring close to home can still bring fresh ideas.|Empruntez une autre rue, observez les saisons ou arrêtez-vous à la bibliothèque du quartier. Explorer près de chez soi peut aussi apporter de nouvelles idées.
Unable to change the PIN.|Impossible de changer le NIP.
Device PIN changed.|NIP de l’appareil modifié.
Unable to save contacts on this device. Please retry.|Impossible d’enregistrer les contacts sur cet appareil. Réessayez.
Contacts saved on this device.|Contacts enregistrés sur cet appareil.
Changes are saved on this device. Cloud sync is unavailable; retry when connected.|Les modifications sont enregistrées sur cet appareil. La synchronisation est indisponible; réessayez une fois connecté.
Changes synced.|Modifications synchronisées.
Saved on this device. Cloud sync failed; retry when connected.|Enregistré sur cet appareil. La synchronisation a échoué; réessayez une fois connecté.
The operation timed out. Check your connection and retry; your account may already exist.|Le délai est dépassé. Vérifiez votre connexion et réessayez; votre compte existe peut-être déjà.
The green goddess|La déesse verte
Burrata & basil|Burrata et basilic
The house burger|Le burger maison
Salmon garden roll|Rouleaux au saumon du jardin
Harvest grain bowl|Bol de grains de saison
Classic margherita|Margherita classique
A bright bowl of avocado, quinoa, crunchy greens, cucumber and our herby tahini dressing.|Un bol coloré d’avocat, quinoa, légumes croquants, concombre et vinaigrette au tahini et aux herbes.
Stone-baked sourdough, slow-roasted tomatoes, creamy burrata and fresh basil.|Pâte au levain cuite sur pierre, tomates rôties, burrata crémeuse et basilic frais.
A grilled patty, cheddar, crisp lettuce, tomato and house sauce in a toasted sesame bun.|Galette grillée, cheddar, laitue croquante, tomate et sauce maison dans un pain au sésame grillé.
Salmon, avocado and cucumber rolls with sesame, ginger and a side of soy sauce.|Rouleaux de saumon, avocat et concombre avec sésame, gingembre et sauce soya.
Roasted sweet potato, ancient grains, baby spinach and lemon dressing.|Patate douce rôtie, grains anciens, jeunes épinards et vinaigrette au citron.
A simple favourite with tomato, mozzarella, basil and extra virgin olive oil.|Un classique avec tomate, mozzarella, basilic et huile d’olive extra vierge.
HOMEMADE BY OLIVE KITCHEN|FAIT MAISON PAR OLIVE KITCHEN
Made in our kitchen and delivered to your door.|Préparé dans notre cuisine et livré à votre porte.
Search Olive’s menu|Rechercher dans le menu d’Olive
Homemade by Olive Kitchen|Fait maison par Olive Kitchen
Vegetarian|Végétarien
Vegan|Végétalien
Meat|Viande
Customize ingredients|Personnaliser les ingrédients
Edited|Modifié
Edit log|Journal des modifications
Tag|Étiquette
Details|Détails
No tag|Aucune étiquette
No changes to save.|Aucune modification à enregistrer.
Search notes|Rechercher dans les notes
No notes found.|Aucune note trouvée.
Pinned|Épinglé
Others|Autres
All notes|Toutes les notes
New private entry|Nouvelle entrée privée
Show less|Voir moins
Yes|Oui
No|Non
Untitled entry|Entrée sans titre
revisions|révisions
Revision|Révision
Before|Avant
After|Après
From|Du
To|Au
Not selected|Non sélectionné
Clear|Effacer
Select time|Choisir l’heure
Next|Suivant
Apply|Appliquer
""".trimIndent().lines().filter { '|' in it }.associate { line ->
    val (english,french)=line.split('|',limit=2)
    english.replace("\\n","\n") to french.replace("\\n","\n")
}
