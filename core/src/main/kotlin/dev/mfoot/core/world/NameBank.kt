package dev.mfoot.core.world

import dev.mfoot.core.rng.DeterministicRandom

/**
 * Banche di nomi e cognomi per nazionalita'.
 *
 * In un mondo generato, i nomi sono la prima cosa che decide se i giocatori sembrano
 * persone o righe di database. Combinando ~35 nomi e ~40 cognomi per nazionalita' si
 * ottengono oltre mille combinazioni plausibili per paese, in pochi KB e senza nessun
 * problema di licenza.
 *
 * I nomi sono inventati o comunissimi di proposito: nessun riferimento a persone reali.
 */
object NameBank {

    private data class Bank(val firstNames: List<String>, val lastNames: List<String>)

    private val banks: Map<String, Bank> = mapOf(
        "Italia" to Bank(
            firstNames = listOf(
                "Marco", "Luca", "Andrea", "Matteo", "Davide", "Simone", "Federico", "Lorenzo",
                "Alessandro", "Giacomo", "Riccardo", "Tommaso", "Nicolo", "Emanuele", "Stefano",
                "Gabriele", "Filippo", "Pietro", "Cristian", "Manuel", "Samuele", "Michele",
                "Daniele", "Fabio", "Enrico", "Alberto", "Giulio", "Leonardo", "Mattia",
                "Raffaele", "Salvatore", "Vincenzo", "Antonio", "Giuseppe", "Domenico",
            ),
            lastNames = listOf(
                "Ferrero", "Brunetti", "Calvani", "Marchetti", "Bellini", "Rizzoli", "Vitale",
                "Sartori", "Moretti", "Fabbri", "Gallo", "Costa", "Riva", "Bianco", "Neri",
                "Longhi", "Serra", "Palmieri", "Zanetti", "Bergamin", "Corradi", "Tosi",
                "Mancuso", "Perrone", "Salvi", "Basso", "Ferrante", "Lombardi", "Guidi",
                "Amato", "Grassi", "Piazza", "Rosati", "Venturi", "Marino", "Cattaneo",
                "Donati", "Rinaldi", "Sanna", "Volpi",
            ),
        ),

        "Francia" to Bank(
            firstNames = listOf(
                "Lucas", "Hugo", "Theo", "Nathan", "Enzo", "Louis", "Jules", "Adrien",
                "Maxime", "Clement", "Antoine", "Baptiste", "Corentin", "Damien", "Florian",
                "Gaetan", "Julien", "Kevin", "Loic", "Mathis", "Noah", "Olivier", "Quentin",
                "Romain", "Sacha", "Thibault", "Valentin", "Yanis", "Amine", "Karim",
                "Ousmane", "Ibrahim", "Mamadou", "Cedric", "Pierre",
            ),
            lastNames = listOf(
                "Lemaire", "Duchamp", "Baptiste", "Marchand", "Vasseur", "Perrin", "Renaud",
                "Gauthier", "Delacroix", "Fournier", "Mercier", "Blanchard", "Chevalier",
                "Rousseau", "Bonnet", "Girard", "Lefevre", "Moulin", "Dupuis", "Charpentier",
                "Aubert", "Barbier", "Colin", "Devaux", "Etienne", "Fontaine", "Guerin",
                "Hamon", "Jacquet", "Lacombe", "Maillard", "Noel", "Pasquier", "Riviere",
                "Sauvage", "Tessier", "Vidal", "Weber", "Diarra", "Traore",
            ),
        ),

        "Germania" to Bank(
            firstNames = listOf(
                "Jonas", "Leon", "Finn", "Luis", "Paul", "Elias", "Noah", "Ben", "Felix",
                "Maximilian", "Moritz", "Jannik", "Tim", "Nico", "Julian", "Lukas", "David",
                "Simon", "Tobias", "Fabian", "Marvin", "Dennis", "Kevin", "Sven", "Torben",
                "Malte", "Hendrik", "Kai", "Bastian", "Christoph", "Matthias", "Sebastian",
                "Florian", "Marcel", "Stefan",
            ),
            lastNames = listOf(
                "Brandt", "Kellner", "Hoffner", "Vogel", "Reinhardt", "Wagner", "Fischer",
                "Bauer", "Kruger", "Schmitt", "Werner", "Lehmann", "Konig", "Huber",
                "Winkler", "Sommer", "Baumann", "Zimmer", "Roth", "Engel", "Berger",
                "Grimm", "Hartmann", "Jager", "Keller", "Lang", "Maurer", "Neumann",
                "Ostermann", "Pfeiffer", "Richter", "Stein", "Thiel", "Ulrich", "Voigt",
                "Wendt", "Ziegler", "Albrecht", "Dietrich", "Ehlers",
            ),
        ),

        "Spagna" to Bank(
            firstNames = listOf(
                "Pablo", "Alvaro", "Sergio", "Javier", "Diego", "Adrian", "Ruben", "Ivan",
                "Marcos", "Hugo", "Mario", "Raul", "Victor", "Alejandro", "Daniel", "Carlos",
                "Miguel", "Jorge", "Angel", "Fernando", "Gonzalo", "Iker", "Unai", "Aitor",
                "Borja", "Nacho", "Rodrigo", "Samuel", "Tomas", "Cesar", "Joaquin", "Isaac",
                "Bruno", "Nicolas", "Andres",
            ),
            lastNames = listOf(
                "Salgado", "Requena", "Vidal", "Cabrera", "Montoya", "Herrera", "Aguilar",
                "Nunez", "Peralta", "Marin", "Iglesias", "Bravo", "Carmona", "Delgado",
                "Esteban", "Fuentes", "Galvez", "Hidalgo", "Izquierdo", "Jimeno", "Lozano",
                "Mendoza", "Nadal", "Olivares", "Pardo", "Quesada", "Roldan", "Serrano",
                "Tejada", "Urena", "Valero", "Zamora", "Arroyo", "Bermudez", "Cuesta",
                "Duran", "Escobar", "Ferrer", "Gimenez", "Hurtado",
            ),
        ),

        "Inghilterra" to Bank(
            firstNames = listOf(
                "Jack", "Harry", "Oliver", "Charlie", "George", "Alfie", "Jacob", "Thomas",
                "Ethan", "Lewis", "Callum", "Ryan", "Connor", "Liam", "Mason", "Kieran",
                "Reece", "Declan", "Aaron", "Bradley", "Curtis", "Dean", "Elliot", "Finley",
                "Gareth", "Isaac", "Joel", "Kyle", "Marcus", "Nathan", "Owen", "Reuben",
                "Toby", "Wesley", "Zach",
            ),
            lastNames = listOf(
                "Whitfield", "Ashcroft", "Hollis", "Bramley", "Kingsley", "Wren", "Carver",
                "Dalton", "Ellery", "Fairbourne", "Grimshaw", "Hadley", "Ingram", "Jarvis",
                "Kendrick", "Lambourne", "Marsden", "Norwood", "Oakley", "Prescott",
                "Quinlan", "Radcliffe", "Sinclair", "Thornton", "Underhill", "Vance",
                "Wexford", "Yarrow", "Bexley", "Chandler", "Draycott", "Everly", "Fenwick",
                "Garrick", "Harlow", "Ledbury", "Merrick", "Penrose", "Rowntree", "Selby",
            ),
        ),

        "Turchia" to Bank(
            firstNames = listOf(
                "Emre", "Burak", "Kerem", "Ozan", "Cengiz", "Yusuf", "Hakan", "Mert",
                "Berkay", "Arda", "Umut", "Serkan", "Onur", "Tolga", "Baris", "Deniz",
                "Efe", "Furkan", "Gokhan", "Halil", "Ismail", "Kaan", "Levent", "Murat",
                "Okan", "Poyraz", "Rasim", "Sinan", "Taner", "Ugur", "Volkan", "Yigit",
                "Zeki", "Ahmet", "Can",
            ),
            lastNames = listOf(
                "Yildirim", "Kaya", "Demir", "Aslan", "Ozturk", "Sahin", "Celik", "Kurt",
                "Koc", "Arslan", "Dogan", "Erdem", "Findik", "Gunes", "Han", "Ipek",
                "Karabulut", "Kilic", "Mutlu", "Nalbant", "Ozdemir", "Polat", "Sonmez",
                "Tekin", "Ulus", "Varol", "Yalcin", "Zorlu", "Bayrak", "Ciftci", "Duman",
                "Ergin", "Gencer", "Hakverdi", "Isik", "Karadeniz", "Levent", "Mermer",
                "Ozkan", "Turan",
            ),
        ),

        "Brasile" to Bank(
            firstNames = listOf(
                "Gabriel", "Rafael", "Lucas", "Matheus", "Bruno", "Felipe", "Vinicius",
                "Gustavo", "Thiago", "Caio", "Douglas", "Everton", "Fernando", "Guilherme",
                "Henrique", "Igor", "Joao", "Leandro", "Murilo", "Nilton", "Otavio",
                "Paulo", "Renan", "Samuel", "Tiago", "Vitor", "Wesley", "Yuri", "Alan",
                "Diego", "Emerson", "Kaique", "Luan", "Marcelo", "Pedro",
            ),
            lastNames = listOf(
                "Ribeiro", "Carvalho", "Nogueira", "Teixeira", "Barbosa", "Moreira",
                "Cardoso", "Pinheiro", "Machado", "Azevedo", "Correia", "Dantas",
                "Esteves", "Freitas", "Gomes", "Henriques", "Ilha", "Jardim", "Lacerda",
                "Macedo", "Nascimento", "Ovidio", "Peixoto", "Quaresma", "Rocha",
                "Siqueira", "Tavares", "Uchoa", "Vasconcelos", "Xavier", "Andrade",
                "Bastos", "Caetano", "Duarte", "Espinosa", "Fontes", "Guedes", "Lisboa",
                "Marinho", "Paiva",
            ),
        ),

        "Argentina" to Bank(
            firstNames = listOf(
                "Santiago", "Mateo", "Benjamin", "Joaquin", "Bautista", "Facundo", "Tomas",
                "Agustin", "Ignacio", "Franco", "Nicolas", "Lautaro", "Emiliano", "Julian",
                "Valentin", "Thiago", "Bruno", "Ciro", "Dante", "Enzo", "Gonzalo",
                "Hernan", "Ivo", "Lisandro", "Maximo", "Nahuel", "Octavio", "Ramiro",
                "Simon", "Tobias", "Uriel", "Alejo", "Cristian", "Damian", "Ezequiel",
            ),
            lastNames = listOf(
                "Ferreyra", "Ojeda", "Quiroga", "Sosa", "Bustos", "Acosta", "Ledesma",
                "Cabral", "Miranda", "Alvarado", "Barrios", "Cardozo", "Dominguez",
                "Escudero", "Farias", "Godoy", "Heredia", "Insaurralde", "Juarez",
                "Leguizamon", "Maidana", "Nieva", "Ortiz", "Paredes", "Quintana", "Rios",
                "Suarez", "Toledo", "Urbina", "Velazquez", "Zapata", "Aguirre", "Benitez",
                "Chaves", "Duarte", "Espindola", "Figueroa", "Gimenez", "Luna", "Molina",
            ),
        ),

        "Portogallo" to Bank(
            firstNames = listOf(
                "Diogo", "Rui", "Nuno", "Ricardo", "Andre", "Miguel", "Tiago", "Bruno",
                "Fabio", "Goncalo", "Hugo", "Ivo", "Joao", "Luis", "Marco", "Nelson",
                "Orlando", "Paulo", "Quim", "Rodrigo", "Sergio", "Telmo", "Vasco",
                "Xavier", "Afonso", "Bernardo", "Cristiano", "Duarte", "Eduardo",
                "Filipe", "Gil", "Henrique", "Ismael", "Jorge", "Leonel",
            ),
            lastNames = listOf(
                "Fonseca", "Almeida", "Marques", "Pereira", "Antunes", "Baptista",
                "Coelho", "Domingues", "Esteves", "Faria", "Guerreiro", "Horta",
                "Infante", "Justino", "Leitao", "Matos", "Neves", "Oliveira", "Pinto",
                "Queiroz", "Ramos", "Simoes", "Torres", "Valente", "Xavier", "Abreu",
                "Braga", "Cunha", "Dias", "Estrela", "Ferraz", "Godinho", "Lourenco",
                "Mendes", "Nunes", "Pacheco", "Reis", "Salgueiro", "Trindade", "Varela",
            ),
        ),

        "Paesi Bassi" to Bank(
            firstNames = listOf(
                "Daan", "Sem", "Lars", "Bram", "Thijs", "Ruben", "Sven", "Jelle", "Koen",
                "Niels", "Stijn", "Tim", "Wouter", "Bas", "Cas", "Dirk", "Erik", "Floris",
                "Gijs", "Hidde", "Ivo", "Joris", "Kees", "Luuk", "Mees", "Noud", "Olaf",
                "Pim", "Quinten", "Roel", "Siem", "Teun", "Vince", "Wessel", "Youri",
            ),
            lastNames = listOf(
                "Vermeer", "Bakker", "Visser", "Kuipers", "Hendriks", "Dekker", "Bosman",
                "Aalders", "Broekhuis", "Cornelissen", "Doornbos", "Evers", "Franken",
                "Groothuis", "Havenaar", "Ijsselstein", "Jansen", "Kloosterman",
                "Linthorst", "Meulendijk", "Nieuwenhuis", "Oosterhuis", "Poelman",
                "Reijnders", "Slotboom", "Terpstra", "Uitenbroek", "Veenstra",
                "Willemsen", "Zandvliet", "Blom", "Doorn", "Elzinga", "Feenstra",
                "Grootveld", "Heemskerk", "Kramer", "Mulder", "Postma", "Wijnen",
            ),
        ),
    )

    val supportedNationalities: List<String> get() = banks.keys.toList()

    fun supports(nationality: String): Boolean = nationality in banks

    /**
     * Genera un nome per la nazionalita' data.
     *
     * Se la nazionalita' non e' fra quelle previste, ripiega su una banca qualsiasi:
     * meglio un nome plausibile ma dell'area sbagliata che un crash a meta' generazione
     * perche' l'admin ha scritto "Groenlandia" nella lista.
     */
    fun generate(nationality: String, rng: DeterministicRandom): Pair<String, String> {
        val bank = banks[nationality] ?: banks.values.first()
        return rng.pick(bank.firstNames) to rng.pick(bank.lastNames)
    }

    /** Quante combinazioni distinte esistono per una nazionalita'. */
    fun combinationsFor(nationality: String): Int {
        val bank = banks[nationality] ?: return 0
        return bank.firstNames.size * bank.lastNames.size
    }
}
