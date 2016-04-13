/*
 * #%L
 * AVATAR
 * %%
 * Copyright (C) 2015 Agile Knowledge Engineering and Semantic Web (AKSW)
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
/**
 *
 */
package org.aksw.avatar.gender;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Lorenz Buehmann
 *
 */
public class LexiconBasedGenderDetector implements GenderDetector {
	
	private String maleNamesPath = "gender/male.txt";
	private String femaleNamesPath = "gender/female.txt";

    private Set<String> male;
    private Set<String> female;

    public LexiconBasedGenderDetector(Set<String> male, Set<String> female) {
        this.male = male;
        this.female = female;
    }

    /*
     * (non-Javadoc) @see
     * org.aksw.sparql2nl.entitysummarizer.gender.GenderDetector#getGender(java.lang.String)
     */
    @Override
    public Gender getGender(String name) {
    	String searchName = name;
    	//check if name is compound
    	String[] words = name.split(" ");
    	if(words.length > 1){
    		searchName = words[0];
    	}
    	
        if (male.contains(searchName)) {
            return Gender.MALE;
        } else if (female.contains(name)) {
            return Gender.FEMALE;
        } else {
            return Gender.UNKNOWN;
        }
    }

    public LexiconBasedGenderDetector() {
        try {
            male = new HashSet<>();
            female = new HashSet<>();
            
            InputStream is = this.getClass().getClassLoader().getResourceAsStream(maleNamesPath);
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String l;
            while((l = br.readLine()) != null){
            	l = l.trim();
                if (!l.startsWith("#") && !l.isEmpty()) {
                    male.add(l);
                }
            }
            br.close();
            
            is = this.getClass().getClassLoader().getResourceAsStream(femaleNamesPath);
            br = new BufferedReader(new InputStreamReader(is));
            while((l = br.readLine()) != null){
            	l = l.trim();
                if (!l.startsWith("#") && !l.isEmpty()) {
                	female.add(l);
                }
            }
            br.close();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
	 * @param maleNamesPath the maleNamesPath to set
	 */
	public void setMaleNamesPath(String maleNamesPath) {
		this.maleNamesPath = maleNamesPath;
	}
	
	/**
	 * @param femaleNamesPath the femaleNamesPath to set
	 */
	public void setFemaleNamesPath(String femaleNamesPath) {
		this.femaleNamesPath = femaleNamesPath;
	}

    public static void main(String[] args) throws Exception {
        LexiconBasedGenderDetector genderDetector = new LexiconBasedGenderDetector();
        System.out.println(genderDetector.getGender("Zinedine Ngonga"));
    }
}
