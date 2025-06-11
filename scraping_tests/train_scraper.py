import requests
from bs4 import BeautifulSoup

def scrape_train_arrivals():
    """
    Scrapes train arrival times for Olsztyn Główny from olsztyn.com.pl.
    """
    url = "https://www.olsztyn.com.pl/pkp,przyjazdy.html"
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36'
    }

    try:
        print(f"Fetching data from {url}...")
        response = requests.get(url, headers=headers)
        response.raise_for_status()  # Raise an exception for bad status codes (4xx or 5xx)
        print("Successfully fetched data.")

        soup = BeautifulSoup(response.content, 'html.parser')
        
        # Find the table body which contains all the rows
        table_body = soup.find('tbody')

        if not table_body:
            print("Error: Could not find the arrivals table (tbody) on the page.")
            return

        arrivals = {}
        # Find all table rows within the tbody
        rows = table_body.find_all('tr')

        print(f"Found {len(rows)} arrival rows. Parsing...")

        for row in rows:
            # The first <td> contains the station name
            station_cell = row.find('td')
            # The second <td> contains one or more <span> elements with times
            time_cells = row.find_all('span')

            if station_cell and time_cells:
                station_name = station_cell.get_text(strip=True)
                arrival_times = [time.get_text(strip=True) for time in time_cells]
                
                if station_name:
                    arrivals[station_name] = arrival_times

        print("\n--- Train Arrivals for Olsztyn Główny ---")
        if arrivals:
            for station, times in arrivals.items():
                print(f"From: {station:<25} | Arrivals: {', '.join(times)}")
        else:
            print("No arrival data could be parsed.")
        print("-----------------------------------------")


    except requests.exceptions.RequestException as e:
        print(f"Error fetching the URL: {e}")
    except Exception as e:
        print(f"An unexpected error occurred: {e}")

if __name__ == "__main__":
    scrape_train_arrivals() 